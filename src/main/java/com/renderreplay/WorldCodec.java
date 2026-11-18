package com.renderreplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.TileObject;

/**
 * Lossless codec for a frame's world calls, as the rows the recorder writes ([method, args...], JSON). Each frame is
 * coded against the one before: unchanged calls and calls that came out as predicted cost a run count; the rest store
 * only the fields that missed. Fields are predicted from the same call one frame earlier: by their previous value, by
 * continuing their last step, or, for a model, by the model that followed it last time. Projections are stored once per
 * frame. Encoder and decoder keep identical state; both start over with reset().
 */
final class WorldCodec
{
	static final byte INT = 0, F32 = 1, F64 = 2, STR = 3, BOOL = 4, PROJ = 5, MODEL = 6;
	private static final Map<String, String> SIGS = new HashMap<>();

	static
	{
		for (Call c : Call.values())
		{
			StringBuilder s = new StringBuilder();
			for (Class<?> t : c.types)
			{
				s.append(t == Projection.class ? 'P' : t == Scene.class ? 'S' : t == Renderable.class ? 'R' : t == TileObject.class ? 'T'
					: t == GameObject.class ? 'G' : t == Model.class ? 'M' : t == SceneTilePaint.class ? 'Q' : t == SceneTileModel.class ? 'Z'
					: t == Set.class ? 'X' : t == int.class ? 'i' : t == long.class ? 'l' : t == float.class ? 'f' : t == double.class ? 'd' : '?');
			}
			SIGS.put(c.methodName + "/" + c.types.length, s.toString());
		}
	}

	private WorldCodec()
	{
	}

	static String sigOf(JsonArray row)
	{
		return SIGS.get(row.get(0).getAsString() + "/" + (row.size() - 1));
	}

	// ---- leaves ----

	private static final class Entry
	{
		JsonArray tpl;      // a row of this shape, whose values are replaced by the leaves
		Object[] leaves;    // Long, Double or String
		byte[] types;
		int shape;
		Object[] pl;        // the leaves one frame earlier, or null
		JsonArray row;      // encoder only
		String shapeKey;    // encoder only
		String id;          // encoder only
	}

	interface ProjRef
	{
		long ref(JsonObject proj);
	}

	// A nested number is an integer leaf when it is one: by its type when the recorder made it, by its value when it was
	// read from a file (then its type is unknown).
	private static boolean integral(JsonPrimitive p)
	{
		Number n = p.getAsNumber();
		if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte)
		{
			return true;
		}
		if (n instanceof Float || n instanceof Double)
		{
			return false;
		}
		double d = n.doubleValue();
		return d == Math.rint(d) && Math.abs(d) < 1e15;
	}

	private static void flattenValue(JsonElement v, List<Object> leaves, List<Byte> types)
	{
		if (v == null || v.isJsonNull())
		{
			return;
		}
		if (v.isJsonPrimitive())
		{
			JsonPrimitive p = v.getAsJsonPrimitive();
			if (p.isBoolean())
			{
				leaves.add(p.getAsBoolean() ? 1L : 0L);
				types.add(BOOL);
			}
			else if (p.isString())
			{
				leaves.add(p.getAsString());
				types.add(STR);
			}
			else if (integral(p))
			{
				leaves.add(p.getAsLong());
				types.add(INT);
			}
			else
			{
				leaves.add(p.getAsDouble());
				types.add(F64);
			}
		}
		else if (v.isJsonArray())
		{
			for (JsonElement e : v.getAsJsonArray())
			{
				flattenValue(e, leaves, types);
			}
		}
		else
		{
			for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet())
			{
				flattenValue(e.getValue(), leaves, types);
			}
		}
	}

	private static void shapeOfValue(JsonElement v, StringBuilder out)
	{
		if (v == null || v.isJsonNull())
		{
			out.append('n');
		}
		else if (v.isJsonPrimitive())
		{
			JsonPrimitive p = v.getAsJsonPrimitive();
			out.append(p.isBoolean() ? 'B' : p.isString() ? 'S' : integral(p) ? 'I' : 'D');
		}
		else if (v.isJsonArray())
		{
			out.append('[');
			for (JsonElement e : v.getAsJsonArray())
			{
				shapeOfValue(e, out);
			}
			out.append(']');
		}
		else
		{
			out.append('{');
			for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet())
			{
				out.append(e.getKey()).append(':');
				shapeOfValue(e.getValue(), out);
			}
			out.append('}');
		}
	}

	static String rowShape(JsonArray row, String sig)
	{
		StringBuilder out = new StringBuilder(64);
		out.append(row.get(0).getAsString()).append('/').append(row.size() - 1).append('|');
		for (int i = 0; i < sig.length(); i++)
		{
			char t = sig.charAt(i);
			JsonElement a = row.get(i + 1);
			boolean nul = a == null || a.isJsonNull();
			if (t == 'P')
			{
				out.append(nul ? "Pn" : "P");
			}
			else if (t == 'f' || t == 'd' || t == 'i' || t == 'l')
			{
				out.append(nul ? 'n' : t == 'f' ? 'F' : t == 'd' ? 'D' : 'I');
			}
			else
			{
				shapeOfValue(a, out);
			}
			out.append('|');
		}
		return out.toString();
	}

	private static boolean isModelRef(JsonElement a)
	{
		if (!a.isJsonObject())
		{
			return false;
		}
		JsonObject o = a.getAsJsonObject();
		return o.size() == 1 && o.has("model") && o.get("model").isJsonPrimitive() && o.get("model").getAsJsonPrimitive().isNumber();
	}

	private static void flatten(JsonArray row, String sig, ProjRef projRef, Entry e)
	{
		List<Object> leaves = new ArrayList<>(16);
		List<Byte> types = new ArrayList<>(16);
		for (int i = 0; i < sig.length(); i++)
		{
			char t = sig.charAt(i);
			JsonElement a = row.get(i + 1);
			if (a == null || a.isJsonNull())
			{
				continue;
			}
			if (t == 'P')
			{
				leaves.add(projRef.ref(a.getAsJsonObject()));
				types.add(PROJ);
			}
			else if (t == 'f')
			{
				leaves.add((double) a.getAsFloat());
				types.add(F32);
			}
			else if (t == 'd')
			{
				leaves.add(a.getAsDouble());
				types.add(F64);
			}
			else if (t == 'i' || t == 'l')
			{
				leaves.add(a.getAsLong());
				types.add(INT);
			}
			else if (t == 'M' && isModelRef(a))
			{
				leaves.add(a.getAsJsonObject().get("model").getAsLong());
				types.add(MODEL);
			}
			else
			{
				flattenValue(a, leaves, types);
			}
		}
		e.leaves = leaves.toArray();
		e.types = new byte[types.size()];
		for (int i = 0; i < e.types.length; i++)
		{
			e.types[i] = types.get(i);
		}
	}

	interface ProjOf
	{
		JsonElement proj(long ref);
	}

	private static JsonElement leafValue(Object v, byte t)
	{
		if (t == BOOL)
		{
			return new JsonPrimitive(((Long) v) != 0);
		}
		if (t == STR)
		{
			return new JsonPrimitive((String) v);
		}
		if (t == F32)
		{
			return new JsonPrimitive((float) (double) (Double) v);
		}
		if (t == F64)
		{
			return new JsonPrimitive((Double) v);
		}
		return new JsonPrimitive((Long) v);
	}

	private static JsonElement unflattenValue(JsonElement tpl, Object[] leaves, byte[] types, int[] pos)
	{
		if (tpl == null || tpl.isJsonNull())
		{
			return JsonNull.INSTANCE;
		}
		if (tpl.isJsonPrimitive())
		{
			int i = pos[0]++;
			return leafValue(leaves[i], types[i]);
		}
		if (tpl.isJsonArray())
		{
			JsonArray out = new JsonArray();
			for (JsonElement e : tpl.getAsJsonArray())
			{
				out.add(unflattenValue(e, leaves, types, pos));
			}
			return out;
		}
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> e : tpl.getAsJsonObject().entrySet())
		{
			out.add(e.getKey(), unflattenValue(e.getValue(), leaves, types, pos));
		}
		return out;
	}

	private static JsonArray unflatten(JsonArray tpl, Object[] leaves, byte[] types, ProjOf projOf)
	{
		String sig = sigOf(tpl);
		JsonArray row = new JsonArray();
		row.add(tpl.get(0));
		int[] pos = {0};
		for (int i = 0; i < sig.length(); i++)
		{
			char t = sig.charAt(i);
			JsonElement a = tpl.get(i + 1);
			if (a == null || a.isJsonNull())
			{
				row.add(JsonNull.INSTANCE);
			}
			else if (t == 'P')
			{
				row.add(projOf.proj((Long) leaves[pos[0]++]));
			}
			else if (t == 'f' || t == 'd' || t == 'i' || t == 'l')
			{
				int k = pos[0]++;
				row.add(leafValue(leaves[k], types[k]));
			}
			else if (t == 'M' && isModelRef(a))
			{
				JsonObject o = new JsonObject();
				o.addProperty("model", (Long) leaves[pos[0]++]);
				row.add(o);
			}
			else
			{
				row.add(unflattenValue(a, leaves, types, pos));
			}
		}
		return row;
	}

	/** Which call in the previous frame this one continues: the object it draws, or its zone or tile. */
	static String identity(JsonArray row)
	{
		String name = row.get(0).getAsString();
		for (int i = 1; i < row.size(); i++)
		{
			JsonElement a = row.get(i);
			if (a != null && a.isJsonObject() && a.getAsJsonObject().has("hash"))
			{
				return name + '#' + a.getAsJsonObject().get("hash").getAsString();
			}
		}
		switch (name)
		{
			case "drawZoneOpaque":
				return "zo#" + row.get(3) + ',' + row.get(4);
			case "drawZoneAlpha":
				return "za#" + row.get(3) + ',' + row.get(4) + ',' + row.get(5);
			case "drawScenePaint":
				return "sp#" + row.get(3) + ',' + row.get(4) + ',' + row.get(5);
			case "drawSceneTileModel":
				return "tm#" + row.get(3) + ',' + row.get(4);
			default:
				return name;
		}
	}

	private static boolean leafEq(Object a, Object b, byte t)
	{
		if (t == F32)
		{
			return Float.floatToIntBits((float) (double) (Double) a) == Float.floatToIntBits((float) (double) (Double) b);
		}
		if (t == F64)
		{
			return Double.doubleToLongBits((Double) a) == Double.doubleToLongBits((Double) b);
		}
		return a.equals(b);
	}

	/** Prediction state, updated the same way by encoder and decoder after every row. */
	private static final class Predictor
	{
		final Map<Long, Long> succ = new HashMap<>();
		final Map<Long, int[]> score = new HashMap<>();

		Object[] predict(Entry base)
		{
			Object[] out = base.leaves.clone();
			for (int i = 0; i < out.length; i++)
			{
				byte t = base.types[i];
				if (t == MODEL)
				{
					Long s = succ.get((Long) base.leaves[i]);
					if (s != null)
					{
						out[i] = s;
					}
				}
				else if (t == INT && base.pl != null && base.pl != base.leaves && base.pl[i] instanceof Long)
				{
					int[] sc = score.get(base.shape * 4096L + i);
					if (sc != null && sc[1] > sc[0])
					{
						long v = (Long) base.leaves[i];
						out[i] = v + (v - (Long) base.pl[i]);
					}
				}
			}
			return out;
		}

		void learn(Entry base, Object[] leaves)
		{
			for (int i = 0; i < leaves.length; i++)
			{
				byte t = base.types[i];
				if (t == MODEL)
				{
					if (!leaves[i].equals(base.leaves[i]))
					{
						succ.put((Long) base.leaves[i], (Long) leaves[i]);
					}
				}
				else if (t == INT && base.pl != null && base.pl != base.leaves && base.pl[i] instanceof Long)
				{
					int[] sc = score.computeIfAbsent(base.shape * 4096L + i, k -> new int[2]);
					long v = (Long) base.leaves[i];
					if (leaves[i].equals(v))
					{
						sc[0]++;
					}
					if (leaves[i].equals(v + (v - (Long) base.pl[i])))
					{
						sc[1]++;
					}
				}
			}
		}
	}

	private static float[] projFloats(JsonObject p)
	{
		List<Float> out = new ArrayList<>(28);
		if (p.has("s"))
		{
			for (JsonElement v : p.getAsJsonArray("s"))
			{
				for (JsonElement x : v.getAsJsonArray())
				{
					out.add(x.getAsFloat());
				}
			}
		}
		if (p.has("m"))
		{
			for (JsonElement x : p.getAsJsonArray("m"))
			{
				out.add(x.getAsFloat());
			}
		}
		float[] a = new float[out.size()];
		for (int i = 0; i < a.length; i++)
		{
			a[i] = out.get(i);
		}
		return a;
	}

	// ops: 0 end, 1 copy n, 2 seek, 3 edit, 4 new row of a known shape, 5 new shape, 6 n rows exactly as predicted

	static final class Encoder
	{
		private final Map<String, Entry> shapes = new HashMap<>(); // shape key -> last entry of that shape (its id in .shape)
		private final Map<String, Integer> strings = new HashMap<>();
		private final List<JsonObject> projTable = new ArrayList<>();
		private final Map<String, Integer> projIds = new HashMap<>();
		private final float[][] lastProj = new float[2][];
		private Predictor pred = new Predictor();
		private List<Entry> prev = new ArrayList<>();

		void reset()
		{
			shapes.clear();
			strings.clear();
			projTable.clear();
			projIds.clear();
			lastProj[0] = lastProj[1] = null;
			pred = new Predictor();
			prev = new ArrayList<>();
		}

		byte[] encode(List<JsonArray> rows)
		{
			Bytes.Out w = new Bytes.Out(1024);
			List<JsonObject> newProjs = new ArrayList<>();
			ProjRef ref = p ->
			{
				String k = p.toString();
				Integer id = projIds.get(k);
				if (id == null)
				{
					id = projTable.size();
					projTable.add(p);
					projIds.put(k, id);
					newProjs.add(p);
				}
				return id;
			};
			List<Entry> ents = new ArrayList<>(rows.size());
			for (JsonArray row : rows)
			{
				String sig = sigOf(row);
				Entry e = new Entry();
				e.row = row;
				e.shapeKey = rowShape(row, sig);
				e.id = identity(row);
				flatten(row, sig, ref, e);
				ents.add(e);
			}
			// projection references relative to this frame's table: 0 is the newest
			for (Entry e : ents)
			{
				for (int i = 0; i < e.types.length; i++)
				{
					if (e.types[i] == PROJ)
					{
						e.leaves[i] = (long) (projTable.size() - 1) - (Long) e.leaves[i];
					}
				}
			}
			Map<String, List<Integer>> byId = new HashMap<>();
			for (int j = 0; j < prev.size(); j++)
			{
				byId.computeIfAbsent(prev.get(j).id, k -> new ArrayList<>()).add(j);
			}
			boolean[] used = new boolean[prev.size()];
			int cursor = 0;
			int[] run = {0, 0}; // count, op
			for (Entry e : ents)
			{
				int q = -1;
				if (cursor < prev.size() && !used[cursor] && prev.get(cursor).id.equals(e.id))
				{
					q = cursor;
				}
				else
				{
					List<Integer> c = byId.get(e.id);
					if (c != null)
					{
						for (int x : c)
						{
							if (!used[x])
							{
								q = x;
								break;
							}
						}
					}
				}
				if (q >= 0 && prev.get(q).shapeKey.equals(e.shapeKey))
				{
					Entry base = prev.get(q);
					e.shape = base.shape;
					e.tpl = base.tpl;
					if (q != cursor)
					{
						flushRun(w, run);
						w.varint(2);
						w.zz(q - cursor);
						cursor = q;
					}
					used[q] = true;
					cursor = q + 1;
					e.pl = base.leaves;
					if (allEq(e.leaves, base.leaves, e.types))
					{
						e.leaves = base.leaves;
						pred.learn(base, e.leaves);
						addRun(w, run, 1);
						continue;
					}
					Object[] p = pred.predict(base);
					boolean asPred = allEq(e.leaves, p, e.types);
					pred.learn(base, e.leaves);
					if (asPred)
					{
						e.leaves = p;
						addRun(w, run, 6);
						continue;
					}
					flushRun(w, run);
					w.varint(3);
					writeLeaves(w, p, e);
					shapes.put(e.shapeKey, e);
				}
				else
				{
					flushRun(w, run);
					Entry s = shapes.get(e.shapeKey);
					if (s != null)
					{
						e.shape = s.shape;
						e.tpl = s.tpl;
						w.varint(4);
						w.varint(s.shape);
						writeLeaves(w, s.leaves, e);
						shapes.put(e.shapeKey, e);
					}
					else
					{
						// a new shape: the row itself, projections blanked, then its leaves against the row's own
						e.shape = shapes.size();
						String sig = sigOf(e.row);
						JsonArray tpl = new JsonArray();
						tpl.add(e.row.get(0));
						for (int i = 0; i < sig.length(); i++)
						{
							JsonElement a = e.row.get(i + 1);
							tpl.add(sig.charAt(i) == 'P' && a != null && !a.isJsonNull() ? new JsonObject() : a);
						}
						e.tpl = tpl;
						w.varint(5);
						w.str(tpl.toString());
						Entry t = new Entry();
						flatten(tpl, sig, p -> 0, t);
						writeLeaves(w, t.leaves, e);
						shapes.put(e.shapeKey, e);
					}
				}
			}
			flushRun(w, run);
			w.varint(0);
			w.varint(newProjs.size());
			for (JsonObject p : newProjs)
			{
				if (!p.has("s"))
				{
					w.u8(2);
					w.str(p.toString());
					continue;
				}
				int kind = p.has("m") ? 1 : 0;
				float[] fl = projFloats(p), base = lastProj[kind];
				w.u8(kind);
				w.varint(fl.length);
				for (int i = 0; i < fl.length; i++)
				{
					int b = Float.floatToIntBits(fl[i]);
					w.varint((base != null && i < base.length ? b ^ Float.floatToIntBits(base[i]) : b) & 0xFFFFFFFFL);
				}
				lastProj[kind] = fl;
			}
			prev = ents;
			return w.toArray();
		}

		private static boolean allEq(Object[] a, Object[] b, byte[] t)
		{
			for (int i = 0; i < a.length; i++)
			{
				if (!leafEq(a[i], b[i], t[i]))
				{
					return false;
				}
			}
			return true;
		}

		private static void addRun(Bytes.Out w, int[] run, int op)
		{
			if (run[1] != op)
			{
				flushRun(w, run);
			}
			run[1] = op;
			run[0]++;
		}

		private static void flushRun(Bytes.Out w, int[] run)
		{
			if (run[0] > 0)
			{
				w.varint(run[1]);
				w.varint(run[0]);
				run[0] = 0;
			}
		}

		private void writeLeaves(Bytes.Out w, Object[] p, Entry e)
		{
			int miss = 0;
			for (int i = 0; i < e.leaves.length; i++)
			{
				if (!leafEq(e.leaves[i], p[i], e.types[i]))
				{
					miss++;
				}
			}
			w.varint(miss);
			int last = -1;
			for (int i = 0; i < e.leaves.length; i++)
			{
				if (leafEq(e.leaves[i], p[i], e.types[i]))
				{
					continue;
				}
				w.varint(i - last - 1);
				last = i;
				byte t = e.types[i];
				Object a = e.leaves[i], b = p[i];
				if (t == INT || t == BOOL || t == PROJ || t == MODEL)
				{
					w.zz((Long) a - (Long) b);
				}
				else if (t == F32)
				{
					w.varint((Float.floatToIntBits((float) (double) (Double) a) ^ Float.floatToIntBits((float) (double) (Double) b)) & 0xFFFFFFFFL);
				}
				else if (t == F64)
				{
					w.varint(Double.doubleToLongBits((Double) a) ^ Double.doubleToLongBits((Double) b));
				}
				else
				{
					Integer id = strings.get(a);
					if (id == null)
					{
						w.varint(0);
						w.str((String) a);
						strings.put((String) a, strings.size());
					}
					else
					{
						w.varint(id + 1);
					}
				}
			}
		}
	}

	static final class Decoder
	{
		private final List<Entry> shapes = new ArrayList<>();
		private final List<String> strings = new ArrayList<>();
		private final List<JsonElement> projTable = new ArrayList<>();
		private final float[][] lastProj = new float[2][];
		private Predictor pred = new Predictor();
		private List<Entry> prev = new ArrayList<>();

		void reset()
		{
			shapes.clear();
			strings.clear();
			projTable.clear();
			lastProj[0] = lastProj[1] = null;
			pred = new Predictor();
			prev = new ArrayList<>();
		}

		List<JsonArray> decode(byte[] bytes)
		{
			Bytes.In r = new Bytes.In(bytes);
			List<Entry> ents = new ArrayList<>(prev.size() + 8);
			int cursor = 0;
			for (; ; )
			{
				int op = r.varint32();
				if (op == 0)
				{
					break;
				}
				if (op == 1 || op == 6)
				{
					int n = r.varint32();
					for (int k = 0; k < n; k++)
					{
						Entry base = prev.get(cursor++);
						Object[] leaves = op == 1 ? base.leaves : pred.predict(base);
						pred.learn(base, leaves);
						ents.add(next(base, leaves));
					}
				}
				else if (op == 2)
				{
					cursor += (int) r.zz();
				}
				else if (op == 3)
				{
					Entry base = prev.get(cursor++);
					Object[] leaves = readLeaves(r, pred.predict(base), base.types);
					pred.learn(base, leaves);
					Entry e = next(base, leaves);
					ents.add(e);
					shapes.set(base.shape, e);
				}
				else if (op == 4)
				{
					Entry s = shapes.get(r.varint32());
					Entry e = new Entry();
					e.tpl = s.tpl;
					e.types = s.types;
					e.shape = s.shape;
					e.leaves = readLeaves(r, s.leaves, s.types);
					shapes.set(s.shape, e);
					ents.add(e);
				}
				else if (op == 5)
				{
					JsonArray tpl = new com.google.gson.JsonParser().parse(r.str()).getAsJsonArray();
					Entry t = new Entry();
					flatten(tpl, sigOf(tpl), p -> 0, t);
					Entry e = new Entry();
					e.tpl = tpl;
					e.types = t.types;
					e.shape = shapes.size();
					e.leaves = readLeaves(r, t.leaves, t.types);
					shapes.add(e);
					ents.add(e);
				}
				else
				{
					throw new IllegalArgumentException("bad op " + op);
				}
			}
			int np = r.varint32();
			for (int k = 0; k < np; k++)
			{
				int kind = r.u8();
				if (kind == 2)
				{
					projTable.add(new com.google.gson.JsonParser().parse(r.str()));
					continue;
				}
				int len = r.varint32();
				float[] base = lastProj[kind], fl = new float[len];
				for (int i = 0; i < len; i++)
				{
					int x = (int) r.varint();
					fl[i] = Float.intBitsToFloat(base != null && i < base.length ? x ^ Float.floatToIntBits(base[i]) : x);
				}
				lastProj[kind] = fl;
				JsonObject p = new JsonObject();
				p.addProperty("proj", kind == 1 ? "float" : "other");
				if (kind == 1)
				{
					JsonArray m = new JsonArray();
					for (int i = 12; i < len; i++)
					{
						m.add(fl[i]);
					}
					p.add("m", m);
				}
				JsonArray s = new JsonArray();
				for (int i = 0; i < 4; i++)
				{
					JsonArray v = new JsonArray();
					for (int j = 0; j < 3; j++)
					{
						v.add(fl[i * 3 + j]);
					}
					s.add(v);
				}
				p.add("s", s);
				projTable.add(p);
			}
			ProjOf projOf = rel -> projTable.get((int) (projTable.size() - 1 - rel));
			List<JsonArray> out = new ArrayList<>(ents.size());
			for (Entry e : ents)
			{
				out.add(unflatten(e.tpl, e.leaves, e.types, projOf));
			}
			prev = ents;
			return out;
		}

		private static Entry next(Entry base, Object[] leaves)
		{
			Entry e = new Entry();
			e.tpl = base.tpl;
			e.types = base.types;
			e.shape = base.shape;
			e.leaves = leaves;
			e.pl = base.leaves;
			return e;
		}

		private Object[] readLeaves(Bytes.In r, Object[] p, byte[] types)
		{
			Object[] leaves = p.clone();
			int n = r.varint32(), i = -1;
			for (int k = 0; k < n; k++)
			{
				i += r.varint32() + 1;
				byte t = types[i];
				Object b = p[i];
				if (t == INT || t == BOOL || t == PROJ || t == MODEL)
				{
					leaves[i] = (Long) b + r.zz();
				}
				else if (t == F32)
				{
					int x = (int) r.varint();
					leaves[i] = (double) Float.intBitsToFloat(x ^ Float.floatToIntBits((float) (double) (Double) b));
				}
				else if (t == F64)
				{
					leaves[i] = Double.longBitsToDouble(r.varint() ^ Double.doubleToLongBits((Double) b));
				}
				else
				{
					int id = r.varint32();
					if (id == 0)
					{
						String s = r.str();
						strings.add(s);
						leaves[i] = s;
					}
					else
					{
						leaves[i] = strings.get(id - 1);
					}
				}
			}
			return leaves;
		}
	}
}
