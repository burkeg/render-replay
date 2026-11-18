package com.renderreplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The model geometry of a recording, compressed. Faces, colours and the other per-face arrays are stored once each and
 * shared. A new pose is stored as the bit difference from the closest recent pose of the same model, so an animation's
 * poses cost little. Options trade exactness for size: vertices rounded to 1/vq units, and poses within poseTol units
 * of an earlier one replaced by it. Models live while a kept frame or a model built on them uses them.
 * Thread-safe: the writer thread adds, playback reads.
 */
final class ModelStore
{
	private static final String[] TOPO = {"f1", "f2", "f3", "c1", "c2", "c3", "tr", "pri", "ft", "fb", "tf", "t1", "t2", "t3"};
	private static final int MAX_DEPTH = 16, RECENT = 8, DECODED = 512;

	private final double vq, poseTol;

	private static final class Arr
	{
		byte[] data;  // deflated zigzag deltas
		char type;    // i int, b byte, s short
		int refs;
		String key;
	}

	private static final class Mod
	{
		byte[] blob;          // deflated, or null for an alias or a tile model kept as JSON
		String json;          // tile models and anything else kept as text
		int base = -1, alias = -1, depth;
		int[] arrays = new int[0];
		int refs, dependents;
		String topo;
	}

	private final Map<Integer, Mod> mods = new HashMap<>();
	private final Map<Integer, Arr> arrays = new HashMap<>();
	private final Map<String, Integer> arrayIds = new HashMap<>();
	private int nextArray;
	private long bytes, added;
	// recent poses per topology, for choosing a base: id and vertices
	private final Map<String, Deque<Object[]>> recent = new HashMap<>();
	private final LinkedHashMap<Integer, float[]> decodedVerts = new LinkedHashMap<Integer, float[]>(64, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, float[]> e)
		{
			return size() > DECODED;
		}
	};

	ModelStore(double vq, double poseTol)
	{
		this.vq = vq;
		this.poseTol = poseTol;
	}

	/** Told the id of every model freed, so its geometry can be offered again if it is drawn again. */
	java.util.function.IntConsumer onFree = id ->
	{
	};

	synchronized boolean has(int id)
	{
		return mods.containsKey(id);
	}

	/** Estimated bytes held. */
	synchronized long bytes()
	{
		return bytes;
	}

	/** Bytes ever added, freed or not (for measuring the rate a recording grows at). */
	synchronized long added()
	{
		return added + bytes;
	}

	synchronized int size()
	{
		return mods.size();
	}

	/** Stores a geometry body (as the recorder writes it). Does nothing if the id is already stored. */
	synchronized void add(int id, JsonObject body)
	{
		if (mods.containsKey(id))
		{
			return;
		}
		Mod m = new Mod();
		mods.put(id, m);
		String kind = body.has("kind") ? body.get("kind").getAsString() : "model";
		if (!"model".equals(kind) || !body.has("vx") || body.get("vx").isJsonNull())
		{
			m.json = body.toString();
			bytes += cost(m);
			return;
		}
		float[] v = verts(body);
		int[] arr = new int[TOPO.length];
		StringBuilder topo = new StringBuilder().append(v.length / 3);
		for (int i = 0; i < TOPO.length; i++)
		{
			arr[i] = addArray(TOPO[i], body.get(TOPO[i]));
			topo.append(',').append(arr[i]);
		}
		m.topo = topo.toString();
		Deque<Object[]> rec = recent.computeIfAbsent(m.topo, k -> new ArrayDeque<>());
		if (poseTol > 0)
		{
			for (Object[] p : rec)
			{
				if (maxDiff((float[]) p[1], v) <= poseTol)
				{
					// close enough to an earlier pose: use that one
					for (int a : arr)
					{
						releaseArray(a);
					}
					m.alias = (Integer) p[0];
					m.topo = null;
					mods.get(m.alias).dependents++;
					bytes += cost(m);
					return;
				}
			}
		}
		m.arrays = arr;
		// the base: the closest recent pose of this topology whose chain is short enough
		Object[] best = null;
		double bestErr = Double.MAX_VALUE;
		for (Object[] p : rec)
		{
			Mod b = mods.get((Integer) p[0]);
			if (b == null || b.depth >= MAX_DEPTH)
			{
				continue;
			}
			double e = sumDiff((float[]) p[1], v, bestErr);
			if (e < bestErr)
			{
				bestErr = e;
				best = p;
			}
		}
		float[] base = best == null ? null : (float[]) best[1];
		if (best != null)
		{
			m.base = (Integer) best[0];
			Mod b = mods.get(m.base);
			b.dependents++;
			m.depth = b.depth + 1;
		}
		Bytes.Out w = new Bytes.Out(64 + v.length * 3);
		w.varint(v.length / 3);
		w.varint(body.has("fn") ? body.get("fn").getAsInt() : 0);
		int[] ov = ints(body.get("ov"));
		for (int i = 0; i < 4; i++)
		{
			w.zz(ov != null && i < ov.length ? ov[i] : 0);
		}
		w.zz(body.has("tp") ? body.get("tp").getAsInt() : 0);
		float[] stored = new float[v.length];
		if (vq > 0)
		{
			for (int i = 0; i < v.length; i++)
			{
				long q = Math.round(v[i] * vq);
				stored[i] = (float) (q / vq);
				long p = base != null ? Math.round(base[i] * vq) : i >= 3 ? Math.round(stored[i - 3] * vq) : 0;
				w.zz(q - p);
			}
		}
		else
		{
			for (int i = 0; i < v.length; i++)
			{
				stored[i] = v[i];
				int p = base != null ? Float.floatToIntBits(base[i]) : i >= 3 ? Float.floatToIntBits(v[i - 3]) : 0;
				w.varint((Float.floatToIntBits(v[i]) ^ p) & 0xFFFFFFFFL);
			}
		}
		m.blob = deflate(w.toArray());
		bytes += cost(m);
		rec.addFirst(new Object[]{id, stored});
		while (rec.size() > RECENT)
		{
			rec.removeLast();
		}
		decodedVerts.put(id, stored);
	}

	/** A kept frame uses this model. */
	synchronized void ref(int id)
	{
		Mod m = mods.get(id);
		if (m != null)
		{
			m.refs++;
		}
	}

	/** A kept frame that used this model was dropped. */
	synchronized void unref(int id)
	{
		Mod m = mods.get(id);
		if (m != null)
		{
			m.refs--;
			maybeFree(id, m);
		}
	}

	private static long cost(Mod m)
	{
		return m.blob != null ? 64 + m.blob.length : m.json != null ? 64 + m.json.length() : 48;
	}

	/** Frees the model if nothing uses it, then its base (or the pose it stands for) if that is now unused too. */
	private void maybeFree(int id, Mod first)
	{
		int cur = id;
		Mod m = first;
		while (m != null && m.refs <= 0 && m.dependents <= 0)
		{
			mods.remove(cur);
			onFree.accept(cur);
			decodedVerts.remove(cur);
			bytes -= cost(m);
			added += cost(m);
			for (int a : m.arrays)
			{
				releaseArray(a);
			}
			if (m.topo != null)
			{
				Deque<Object[]> rec = recent.get(m.topo);
				final int gone = cur;
				if (rec != null)
				{
					rec.removeIf(p -> (Integer) p[0] == gone);
				}
			}
			int next = m.alias >= 0 ? m.alias : m.base;
			if (next < 0)
			{
				return;
			}
			m = mods.get(next);
			if (m != null)
			{
				m.dependents--;
			}
			cur = next;
		}
	}

	/** The geometry body for playback (the shape FileReplay.model reads), or null if it is not stored. */
	synchronized JsonObject body(int id)
	{
		Mod m = mods.get(id);
		if (m == null)
		{
			return null;
		}
		if (m.alias >= 0)
		{
			return body(m.alias);
		}
		if (m.json != null)
		{
			return new com.google.gson.JsonParser().parse(m.json).getAsJsonObject();
		}
		Bytes.In r = new Bytes.In(inflate(m.blob));
		int vn = r.varint32(), fn = r.varint32();
		int[] ov = {(int) r.zz(), (int) r.zz(), (int) r.zz(), (int) r.zz()};
		int tp = (int) r.zz();
		float[] v = decodeVerts(id, m, r, vn);
		JsonObject b = new JsonObject();
		b.add("vx", floats(v, 0, vn));
		b.add("vy", floats(v, 1, vn));
		b.add("vz", floats(v, 2, vn));
		for (int i = 0; i < TOPO.length; i++)
		{
			if (m.arrays[i] >= 0)
			{
				b.add(TOPO[i], arrayJson(arrays.get(m.arrays[i])));
			}
		}
		b.addProperty("vn", vn);
		b.addProperty("fn", fn);
		JsonArray o = new JsonArray();
		for (int x : ov)
		{
			o.add(x);
		}
		b.add("ov", o);
		b.addProperty("tp", tp);
		b.addProperty("kind", "model");
		return b;
	}

	private float[] decodeVerts(int id, Mod m, Bytes.In r, int vn)
	{
		float[] cached = decodedVerts.get(id);
		if (cached != null)
		{
			return cached;
		}
		float[] base = null;
		if (m.base >= 0)
		{
			Mod b = mods.get(m.base);
			base = decodedVerts.get(m.base);
			if (base == null)
			{
				Bytes.In br = new Bytes.In(inflate(b.blob));
				int bvn = br.varint32();
				br.varint32();
				for (int i = 0; i < 5; i++)
				{
					br.zz();
				}
				base = decodeVerts(m.base, b, br, bvn);
			}
		}
		float[] v = new float[vn * 3];
		for (int i = 0; i < v.length; i++)
		{
			if (vq > 0)
			{
				long p = base != null ? Math.round(base[i] * vq) : i >= 3 ? Math.round(v[i - 3] * vq) : 0;
				v[i] = (float) ((r.zz() + p) / vq);
			}
			else
			{
				int p = base != null ? Float.floatToIntBits(base[i]) : i >= 3 ? Float.floatToIntBits(v[i - 3]) : 0;
				v[i] = Float.intBitsToFloat((int) r.varint() ^ p);
			}
		}
		decodedVerts.put(id, v);
		return v;
	}

	// ---- shared arrays ----

	private int addArray(String name, JsonElement e)
	{
		if (e == null || e.isJsonNull() || !e.isJsonArray())
		{
			return -1;
		}
		JsonArray a = e.getAsJsonArray();
		char type = name.equals("ft") ? 's' : (name.equals("tr") || name.equals("pri") || name.equals("fb") || name.equals("tf")) ? 'b' : 'i';
		long[] vals = new long[a.size()];
		for (int i = 0; i < vals.length; i++)
		{
			vals[i] = a.get(i).getAsLong();
		}
		String key = type + ":" + vals.length + ":" + Arrays.hashCode(vals) + ":" + hash2(vals);
		Integer id = arrayIds.get(key);
		if (id != null)
		{
			arrays.get(id).refs++;
			return id;
		}
		Bytes.Out w = new Bytes.Out(vals.length * 2 + 8);
		w.varint(vals.length);
		long prev = 0;
		for (long x : vals)
		{
			w.zz(x - prev);
			prev = x;
		}
		Arr r = new Arr();
		r.data = deflate(w.toArray());
		r.type = type;
		r.refs = 1;
		r.key = key;
		id = nextArray++;
		arrays.put(id, r);
		arrayIds.put(key, id);
		bytes += 48 + r.data.length;
		return id;
	}

	private void releaseArray(int id)
	{
		if (id < 0)
		{
			return;
		}
		Arr a = arrays.get(id);
		if (a != null && --a.refs <= 0)
		{
			arrays.remove(id);
			arrayIds.remove(a.key);
			bytes -= 48 + a.data.length;
			added += 48 + a.data.length;
		}
	}

	private static JsonArray arrayJson(Arr a)
	{
		Bytes.In r = new Bytes.In(inflate(a.data));
		int n = r.varint32();
		JsonArray out = new JsonArray();
		long prev = 0;
		for (int i = 0; i < n; i++)
		{
			prev += r.zz();
			out.add(prev);
		}
		return out;
	}

	// ---- helpers ----

	private static float[] verts(JsonObject b)
	{
		JsonArray x = b.getAsJsonArray("vx"), y = b.getAsJsonArray("vy"), z = b.getAsJsonArray("vz");
		int n = x.size();
		float[] v = new float[n * 3];
		for (int i = 0; i < n; i++)
		{
			v[i * 3] = x.get(i).getAsFloat();
			v[i * 3 + 1] = y.get(i).getAsFloat();
			v[i * 3 + 2] = z.get(i).getAsFloat();
		}
		return v;
	}

	private static int[] ints(JsonElement e)
	{
		if (e == null || !e.isJsonArray())
		{
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = a.get(i).getAsInt();
		}
		return out;
	}

	private static JsonArray floats(float[] v, int c, int n)
	{
		JsonArray a = new JsonArray();
		for (int i = 0; i < n; i++)
		{
			a.add(v[i * 3 + c]);
		}
		return a;
	}

	private static double maxDiff(float[] a, float[] b)
	{
		if (a.length != b.length)
		{
			return Double.MAX_VALUE;
		}
		double m = 0;
		for (int i = 0; i < a.length; i++)
		{
			m = Math.max(m, Math.abs(a[i] - b[i]));
		}
		return m;
	}

	private static double sumDiff(float[] a, float[] b, double stop)
	{
		if (a.length != b.length)
		{
			return Double.MAX_VALUE;
		}
		double s = 0;
		for (int i = 0; i < a.length && s < stop; i++)
		{
			s += Math.abs(a[i] - b[i]);
		}
		return s;
	}

	private static long hash2(long[] v)
	{
		long h = 1125899906842597L;
		for (long x : v)
		{
			h = 31 * h + x * 0x9E3779B97F4A7C15L;
		}
		return h;
	}

	static byte[] deflate(byte[] in)
	{
		Deflater d = new Deflater(6, true);
		d.setInput(in);
		d.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, in.length / 2));
		byte[] buf = new byte[8192];
		while (!d.finished())
		{
			out.write(buf, 0, d.deflate(buf));
		}
		d.end();
		return out.toByteArray();
	}

	static byte[] inflate(byte[] in)
	{
		Inflater i = new Inflater(true);
		i.setInput(in);
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 3);
		byte[] buf = new byte[8192];
		try
		{
			while (!i.finished())
			{
				int n = i.inflate(buf);
				if (n == 0 && (i.needsInput() || i.needsDictionary()))
				{
					break;
				}
				out.write(buf, 0, n);
			}
		}
		catch (DataFormatException e)
		{
			throw new IllegalArgumentException("damaged data", e);
		}
		finally
		{
			i.end();
		}
		return out.toByteArray();
	}

	/** Ids stored (for tests). */
	synchronized List<Integer> ids()
	{
		return new ArrayList<>(mods.keySet());
	}
}
