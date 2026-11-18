package com.renderreplay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.SceneTilePaint;

/**
 * A recording held compressed in memory, at one of the presets. Frames come in from the recorder's writer thread and
 * are kept in batches of 60: the frames' times and positions, their world calls (WorldCodec, every n-th frame for the
 * lower presets) and their 2D layer (UiCodec), deflated once the batch is complete. Model geometry is kept once
 * (ModelStore). When the budget is exceeded, the oldest frames go, a 2D-layer anchor span at a time.
 * Playback reads frames back through the accessors: calls are rebuilt the way an imported recording's are.
 */
@lombok.extern.slf4j.Slf4j
final class FrameStore
{
	static final int GROUP = 60;
	private static final int PENDING_FRAMES = 240;

	interface Scenes
	{
		/** The scene object recorded under this id, or null. */
		Object scene(int id);
	}

	final Presets.Preset preset;
	private final UiCodec.Opts uiOpts;
	private final ModelStore models;
	private final WorldCodec.Encoder wenc = new WorldCodec.Encoder();
	private final UiCodec.Encoder uenc;
	private final Gson gson;
	private final Scenes scenes;

	private static final class Group
	{
		final int first;
		int count;
		// while open
		long[] wall = new long[GROUP];
		int[] tick = new int[GROUP], scale = new int[GROUP], cycle = new int[GROUP], scene = new int[GROUP];
		int[] uiWidth = new int[GROUP], uiN = new int[GROUP], uiFlags = new int[GROUP];
		float[] px = new float[GROUP], pz = new float[GROUP], yaw = new float[GROUP];
		byte[][] ui = new byte[GROUP][];
		Bytes.Out world = new Bytes.Out(2048);
		final Set<Integer> modelIds = new HashSet<>();
		// once closed
		byte[] meta, uiAll, worldZ;
		int[] ids;
		long bytes;

		Group(int first)
		{
			this.first = first;
		}

		boolean closed()
		{
			return meta != null;
		}
	}

	/** A batch's per-frame data, unpacked. */
	private static final class Meta
	{
		long[] wall;
		int[] tick, scale, cycle, scene, uiWidth, uiN, uiFlags, uiOff;
		float[] px, pz, yaw;
	}

	private final List<Group> groups = new ArrayList<>();
	private final List<Object> sceneList = new ArrayList<>();
	private final Map<Object, Integer> sceneIndex = new java.util.IdentityHashMap<>();
	private int base, next;
	private long closedBytes, closedEver;
	private long rateWall0 = -1, rateBytes0, lastWall, firstWall = -1;
	private final Map<Integer, Object> pending = new ConcurrentHashMap<>();
	// geometry ids stored or waiting to be: anything else the recorder should offer again when it is drawn
	private final Set<Integer> known = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Integer> pendingAt = new HashMap<>();
	private final Map<String, JsonArray> firstSeen = new HashMap<>();

	FrameStore(Presets.Preset preset, Gson gson, Scenes scenes)
	{
		this.preset = preset;
		this.uiOpts = preset.uiOpts();
		this.uenc = new UiCodec.Encoder(uiOpts);
		this.models = new ModelStore(preset.vq, preset.poseTol);
		this.models.onFree = known::remove;
		this.gson = gson;
		this.scenes = scenes;
	}

	/** New model geometry seen by the recorder (any thread); kept until a stored frame draws it. */
	void offerBody(int id, Object body)
	{
		pending.put(id, body);
		known.add(id);
	}

	/** True when the geometry with this id is neither stored nor waiting: the recorder should offer it again. */
	boolean lost(int id)
	{
		return !known.contains(id);
	}

	// ---- recording (writer thread) ----

	/**
	 * Adds one frame. rows are the frame's calls as the recorder describes them ([method, args...]); pixels the 2D
	 * layer (or null), width its width; mm the minimap {cx, cy, r} in layer pixels (or null).
	 */
	synchronized void append(long wallMs, int tick, int scale, int cycle, float[] player, float yaw, Object scene,
		List<Object[]> rows, int[] pixels, int width, int[] mm)
	{
		int f = next++;
		if (firstWall < 0)
		{
			firstWall = wallMs;
		}
		lastWall = wallMs;
		groupLastWall = wallMs;
		Group g = groups.isEmpty() ? null : groups.get(groups.size() - 1);
		if (g == null || g.closed() || g.count == GROUP)
		{
			if (g != null && !g.closed())
			{
				close(g);
			}
			g = new Group(f);
			groups.add(g);
			wenc.reset();
		}
		int k = g.count++;
		g.wall[k] = wallMs;
		g.tick[k] = tick;
		g.scale[k] = scale;
		g.cycle[k] = cycle;
		g.px[k] = player == null ? Float.NaN : player[0];
		g.pz[k] = player == null ? Float.NaN : player[1];
		g.yaw[k] = yaw;
		g.scene[k] = sceneId(scene);
		if (pixels != null && width > 0)
		{
			g.ui[k] = uenc.encode(pixels, width, f, yaw, mm);
			g.uiWidth[k] = width;
			g.uiN[k] = pixels.length;
			g.uiFlags[k] = 4 | (uenc.wasKey ? 1 : 0) | (uenc.wasStandalone ? 2 : 0);
		}
		else
		{
			g.ui[k] = new byte[0];
		}
		if (f % preset.every == 0)
		{
			List<JsonArray> world = worldRows(rows);
			for (JsonArray r : world)
			{
				for (JsonElement a : r)
				{
					if (a.isJsonObject() && a.getAsJsonObject().size() == 1 && a.getAsJsonObject().has("model"))
					{
						int id = a.getAsJsonObject().get("model").getAsInt();
						if (!models.has(id))
						{
							Object body = pending.remove(id);
							pendingAt.remove(id);
							if (body != null)
							{
								models.add(id, body instanceof JsonObject ? (JsonObject) body : gson.toJsonTree(body).getAsJsonObject());
							}
						}
						if (models.has(id) && g.modelIds.add(id))
						{
							models.ref(id);
						}
					}
				}
			}
			byte[] b = wenc.encode(world);
			g.world.varint(b.length);
			g.world.bytes(b);
		}
		// geometry no stored frame drew soon after it appeared is dropped
		for (Integer id : pending.keySet())
		{
			pendingAt.putIfAbsent(id, f);
		}
		pendingAt.entrySet().removeIf(e ->
		{
			if (f - e.getValue() > PENDING_FRAMES + 2 * preset.every)
			{
				pending.remove(e.getKey());
				if (!models.has(e.getKey()))
				{
					known.remove(e.getKey());
				}
				return true;
			}
			return false;
		});
		if (g.count == GROUP)
		{
			close(g);
		}
	}

	/** Finishes the batch being filled (when recording stops). */
	synchronized void finish()
	{
		if (!groups.isEmpty() && !groups.get(groups.size() - 1).closed())
		{
			close(groups.get(groups.size() - 1));
		}
		for (Integer id : pending.keySet())
		{
			if (!models.has(id))
			{
				known.remove(id);
			}
		}
		pending.clear();
		pendingAt.clear();
	}

	private int sceneId(Object scene)
	{
		if (scene == null)
		{
			return -1;
		}
		Integer i = sceneIndex.get(scene);
		if (i == null)
		{
			i = sceneList.size();
			sceneList.add(scene);
			sceneIndex.put(scene, i);
		}
		return i;
	}

	private static final java.util.Set<String> WORLD = new java.util.HashSet<>(Arrays.asList("drawScene", "preSceneDraw", "postSceneDraw",
		"postDrawScene", "drawScenePaint", "drawSceneTileModel", "drawDynamic", "drawTemp", "drawPass", "drawZoneOpaque", "drawZoneAlpha", "draw"));

	/** The calls playback replays, as JSON rows: worker numbers as the plugin keeps them, poses frozen if asked. */
	private List<JsonArray> worldRows(List<Object[]> rows)
	{
		List<JsonArray> out = new ArrayList<>(rows.size());
		for (Object[] r : rows)
		{
			if (r.length == 0 || !(r[0] instanceof String) || !WORLD.contains(r[0]))
			{
				continue;
			}
			Call m = Call.find((String) r[0], r.length - 1);
			if (m == null || m.isFrameEnd())
			{
				continue;
			}
			JsonArray row = gson.toJsonTree(r).getAsJsonArray();
			if (m == Call.DRAW_DYNAMIC_WORKER && row.get(1).getAsInt() > 0)
			{
				row.set(1, new com.google.gson.JsonPrimitive(0));
			}
			if (preset.freeze && (m == Call.DRAW_DYNAMIC || m == Call.DRAW_DYNAMIC_WORKER || m == Call.DRAW_TEMP))
			{
				String id = WorldCodec.identity(row);
				JsonArray first = firstSeen.get(id);
				if (first == null)
				{
					firstSeen.put(id, row);
				}
				else if (first.size() == row.size())
				{
					for (int i = 1; i < row.size(); i++)
					{
						JsonElement a = row.get(i), b = first.get(i);
						if (a.isJsonObject() && b.isJsonObject())
						{
							if (a.getAsJsonObject().has("model"))
							{
								row.set(i, b);
							}
							else if (a.getAsJsonObject().has("h") && b.getAsJsonObject().has("h"))
							{
								JsonObject o = a.getAsJsonObject().deepCopy();
								o.add("h", b.getAsJsonObject().get("h"));
								row.set(i, o);
							}
						}
					}
				}
			}
			out.add(row);
		}
		return out;
	}

	private void close(Group g)
	{
		Bytes.Out m = new Bytes.Out(g.count * 16);
		long pw = 0;
		int pt = 0, ps = 0, pc = 0, puw = 0, pun = 0;
		int[] pf = {0, 0, 0};
		int total = 0;
		for (int k = 0; k < g.count; k++)
		{
			m.zz(g.wall[k] - pw);
			m.zz(g.tick[k] - pt);
			m.zz(g.scale[k] - ps);
			m.zz(g.cycle[k] - pc);
			m.zz(g.scene[k]);
			m.zz(g.uiWidth[k] - puw);
			m.zz(g.uiN[k] - pun);
			m.varint(g.uiFlags[k]);
			m.varint(g.ui[k].length);
			int[] fl = {Float.floatToIntBits(g.px[k]), Float.floatToIntBits(g.pz[k]), Float.floatToIntBits(g.yaw[k])};
			for (int i = 0; i < 3; i++)
			{
				m.varint((fl[i] ^ pf[i]) & 0xFFFFFFFFL);
				pf[i] = fl[i];
			}
			pw = g.wall[k];
			pt = g.tick[k];
			ps = g.scale[k];
			pc = g.cycle[k];
			puw = g.uiWidth[k];
			pun = g.uiN[k];
			total += g.ui[k].length;
		}
		g.meta = ModelStore.deflate(m.toArray());
		g.uiAll = new byte[total];
		int p = 0;
		for (int k = 0; k < g.count; k++)
		{
			System.arraycopy(g.ui[k], 0, g.uiAll, p, g.ui[k].length);
			p += g.ui[k].length;
		}
		g.worldZ = ModelStore.deflate(g.world.toArray());
		g.ids = new int[g.modelIds.size()];
		int i = 0;
		for (int id : g.modelIds)
		{
			g.ids[i++] = id;
		}
		g.wall = null;
		g.tick = g.scale = g.cycle = g.scene = g.uiWidth = g.uiN = g.uiFlags = null;
		g.px = g.pz = g.yaw = null;
		g.ui = null;
		g.world = null;
		g.bytes = 160 + g.meta.length + g.uiAll.length + g.worldZ.length + 4L * g.ids.length;
		closedBytes += g.bytes;
		closedEver += g.bytes;
		// the steady rate is measured from 10 seconds in, past the burst of everything seen for the first time
		long w = groupLastWall;
		if (rateWall0 < 0 && firstWall >= 0 && w - firstWall >= 10_000)
		{
			rateWall0 = w;
			rateBytes0 = closedEver + models.added();
		}
	}

	/** Drops the oldest anchor spans while over budget, keeping at least minFrames and the span being filled. */
	synchronized void trim(long budget, int minFrames, int maxFrames)
	{
		int span = uiOpts.key * uiOpts.anchorEvery;
		while ((bytes() > budget || size() > maxFrames) && groups.size() > 1)
		{
			int end = (base / span + 1) * span;
			if (end >= next - GROUP || size() - (end - base) < minFrames)
			{
				return;
			}
			while (!groups.isEmpty() && groups.get(0).first < end && groups.get(0).closed())
			{
				Group g = groups.remove(0);
				closedBytes -= g.bytes;
				for (int id : g.ids)
				{
					models.unref(id);
				}
			}
			base = end;
			decodedGroups.clear();
			metaCache.clear();
			udec = null;
		}
	}

	private long groupLastWall;

	/** Frames dropped from the start of the recording to stay within its memory. */
	synchronized int dropped()
	{
		return base;
	}

	/** Bytes per second this recording grows at in the long run, or 0 until it has run long enough to tell. */
	synchronized double steadyRate()
	{
		if (rateWall0 < 0 || lastWall - rateWall0 < 10_000)
		{
			return 0;
		}
		return (closedEver + models.added() - rateBytes0) * 1000.0 / (lastWall - rateWall0);
	}

	/** Estimated bytes held: batches, models and the batch being filled. */
	synchronized long bytes()
	{
		long open = 0;
		Group g = groups.isEmpty() ? null : groups.get(groups.size() - 1);
		if (g != null && !g.closed())
		{
			open = 3000 + g.world.n;
			for (int k = 0; k < g.count; k++)
			{
				open += g.ui[k].length;
			}
		}
		return closedBytes + models.bytes() + open;
	}

	synchronized long uiBytes()
	{
		long b = 0;
		for (Group g : groups)
		{
			if (g.closed())
			{
				b += g.uiAll.length;
			}
			else
			{
				for (int k = 0; k < g.count; k++)
				{
					b += g.ui[k].length;
				}
			}
		}
		return b;
	}

	synchronized int size()
	{
		return next - base;
	}

	// ---- frame data ----

	private final LinkedHashMap<Group, Meta> metaCache = new LinkedHashMap<Group, Meta>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Group, Meta> e)
		{
			return size() > 64;
		}
	};

	private Group groupOf(int f)
	{
		int i = (f - groups.get(0).first) / GROUP;
		return groups.get(Math.max(0, Math.min(groups.size() - 1, i)));
	}

	private Meta meta(Group g)
	{
		if (!g.closed())
		{
			Meta m = new Meta();
			m.wall = g.wall;
			m.tick = g.tick;
			m.scale = g.scale;
			m.cycle = g.cycle;
			m.scene = g.scene;
			m.uiWidth = g.uiWidth;
			m.uiN = g.uiN;
			m.uiFlags = g.uiFlags;
			m.px = g.px;
			m.pz = g.pz;
			m.yaw = g.yaw;
			return m;
		}
		Meta m = metaCache.get(g);
		if (m != null)
		{
			return m;
		}
		m = new Meta();
		int n = g.count;
		m.wall = new long[n];
		m.tick = new int[n];
		m.scale = new int[n];
		m.cycle = new int[n];
		m.scene = new int[n];
		m.uiWidth = new int[n];
		m.uiN = new int[n];
		m.uiFlags = new int[n];
		m.uiOff = new int[n + 1];
		m.px = new float[n];
		m.pz = new float[n];
		m.yaw = new float[n];
		Bytes.In r = new Bytes.In(ModelStore.inflate(g.meta));
		long pw = 0;
		int pt = 0, ps = 0, pc = 0, puw = 0, pun = 0;
		int[] pf = {0, 0, 0};
		for (int k = 0; k < n; k++)
		{
			m.wall[k] = pw += r.zz();
			m.tick[k] = pt += (int) r.zz();
			m.scale[k] = ps += (int) r.zz();
			m.cycle[k] = pc += (int) r.zz();
			m.scene[k] = (int) r.zz();
			m.uiWidth[k] = puw += (int) r.zz();
			m.uiN[k] = pun += (int) r.zz();
			m.uiFlags[k] = r.varint32();
			m.uiOff[k + 1] = m.uiOff[k] + r.varint32();
			for (int i = 0; i < 3; i++)
			{
				pf[i] ^= (int) r.varint();
			}
			m.px[k] = Float.intBitsToFloat(pf[0]);
			m.pz[k] = Float.intBitsToFloat(pf[1]);
			m.yaw[k] = Float.intBitsToFloat(pf[2]);
		}
		metaCache.put(g, m);
		return m;
	}

	/** Frame data of the i-th kept frame (0 = oldest): {wallMs} etc. through the small accessors below. */
	synchronized long wallMs(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).wall[f - g.first];
	}

	synchronized int tick(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).tick[f - g.first];
	}

	synchronized int scale(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).scale[f - g.first];
	}

	synchronized int cycle(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).cycle[f - g.first];
	}

	synchronized float playerX(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).px[f - g.first];
	}

	synchronized float playerZ(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).pz[f - g.first];
	}

	synchronized Object scene(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		int s = meta(g).scene[f - g.first];
		return s >= 0 && s < sceneList.size() ? sceneList.get(s) : null;
	}

	/** Index of the last kept frame at or before wall time t (binary search), or 0. */
	synchronized int indexAt(long t)
	{
		int lo = 0, hi = size() - 1;
		while (lo < hi)
		{
			int mid = (lo + hi + 1) >>> 1;
			if (wallMs(mid) <= t)
			{
				lo = mid;
			}
			else
			{
				hi = mid - 1;
			}
		}
		return lo;
	}

	// ---- world calls for playback ----

	private static final class Decoded
	{
		final Map<Integer, List<Object[]>> calls = new HashMap<>();
		final Map<Integer, String[]> ids = new HashMap<>();
	}

	private final LinkedHashMap<Group, Decoded> decodedGroups = new LinkedHashMap<Group, Decoded>(4, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Group, Decoded> e)
		{
			return size() > 3;
		}
	};
	private final ArrayPool arrays = new ArrayPool();
	private final LinkedHashMap<Integer, Object> modelCache = new LinkedHashMap<Integer, Object>(256, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Object> e)
		{
			return size() > 3000;
		}
	};

	private Decoded decoded(Group g)
	{
		Decoded d = decodedGroups.get(g);
		if (d != null)
		{
			return d;
		}
		d = new Decoded();
		byte[] raw = g.closed() ? ModelStore.inflate(g.worldZ) : g.world.toArray();
		Bytes.In r = new Bytes.In(raw);
		WorldCodec.Decoder dec = new WorldCodec.Decoder();
		Map<String, Object> cache = new HashMap<>();
		for (int f = g.first; f < g.first + g.count; f++)
		{
			if (f % preset.every != 0)
			{
				continue;
			}
			if (r.done())
			{
				break;
			}
			int len = r.varint32();
			byte[] b = Arrays.copyOfRange(r.b, r.p, r.p + len);
			r.p += len;
			List<JsonArray> rows = dec.decode(b);
			List<Object[]> calls = new ArrayList<>(rows.size());
			String[] ids = new String[rows.size()];
			for (JsonArray row : rows)
			{
				Call m = Call.find(row.get(0).getAsString(), row.size() - 1);
				if (m == null)
				{
					continue;
				}
				Object[] args = new Object[m.types.length];
				boolean missing = false;
				for (int i = 0; i < args.length; i++)
				{
					args[i] = arg(row.get(i + 1), m.types[i], cache);
					JsonElement cell = row.get(i + 1);
					if (args[i] == null && m.types[i] == net.runelite.api.Model.class && cell != null && cell.isJsonObject() && cell.getAsJsonObject().has("model"))
					{
						missing = true;
					}
				}
				if (missing)
				{
					continue; // geometry that was never kept: drawing it would only fail in the renderer
				}
				ids[calls.size()] = WorldCodec.identity(row);
				calls.add(new Object[]{m, args});
			}
			d.calls.put(f, calls);
			d.ids.put(f, ids);
		}
		decodedGroups.put(g, d);
		return d;
	}

	/** One recorded argument back as the object the renderer is handed (as an import builds it). */
	private Object arg(JsonElement cell, Class<?> type, Map<String, Object> cache)
	{
		if (cell == null || cell.isJsonNull())
		{
			return null;
		}
		if (cell.isJsonPrimitive())
		{
			com.google.gson.JsonPrimitive p = cell.getAsJsonPrimitive();
			if (p.isBoolean())
			{
				return p.getAsBoolean();
			}
			if (p.isNumber())
			{
				return FileReplay.convert(p.getAsNumber(), type);
			}
			return type == String.class ? p.getAsString() : null;
		}
		String key = cell.toString();
		Object hit = cache.get(key);
		if (hit != null)
		{
			return hit;
		}
		Object made = null;
		if (cell.isJsonArray())
		{
			if (type == SceneTilePaint.class)
			{
				made = FileReplay.paint(cell.getAsJsonArray());
			}
		}
		else
		{
			JsonObject o = cell.getAsJsonObject();
			if (o.has("scene"))
			{
				made = scenes.scene(o.get("scene").getAsInt());
			}
			else if (o.has("model"))
			{
				int id = o.get("model").getAsInt();
				made = modelCache.get(id);
				if (made == null)
				{
					JsonObject b = models.body(id);
					made = b == null ? null : FileReplay.model(b, arrays);
					if (made != null)
					{
						modelCache.put(id, made);
					}
				}
			}
			else if (o.has("tilemodel"))
			{
				JsonObject b = models.body(o.get("tilemodel").getAsInt());
				made = b == null ? null : FileReplay.tileModel(b);
			}
			else if (o.has("go"))
			{
				made = FileReplay.gameObject(o);
			}
			else if (o.has("tobj"))
			{
				made = FileReplay.tileObject(o);
			}
			else if (o.has("rend"))
			{
				made = FileReplay.renderable(o);
			}
			else if (o.has("proj"))
			{
				// a boat's transform: the renderer reads its matrix, so it gets a frozen copy of that matrix
				if (o.has("m") && o.get("m").isJsonArray() && o.getAsJsonArray("m").size() == 16)
				{
					float[] mat = new float[16];
					for (int i = 0; i < 16; i++)
					{
						mat[i] = o.getAsJsonArray("m").get(i).getAsFloat();
					}
					made = FrozenProjection.ofMatrix(mat);
				}
				else
				{
					made = o.has("s") ? new FileReplay.LiveProjection(FileReplay.projection(o)) : null;
				}
			}
			else if (o.has("set"))
			{
				Set<Integer> set = new HashSet<>();
				for (JsonElement x : o.getAsJsonArray("set"))
				{
					set.add(x.getAsInt());
				}
				made = set;
			}
		}
		if (made != null)
		{
			cache.put(key, made);
		}
		return made;
	}

	/** The world calls playback replays for the i-th kept frame: {Call, args} each. */
	synchronized List<Object[]> calls(int i)
	{
		int f = base + i, k = preset.every;
		int a = f - Math.floorMod(f, k);
		if (a < base)
		{
			a += k;
		}
		if (a >= next)
		{
			return java.util.Collections.emptyList();
		}
		Decoded da = decoded(groupOf(a));
		List<Object[]> ca = da.calls.get(a);
		if (ca == null)
		{
			return java.util.Collections.emptyList();
		}
		if (k == 1 || !preset.interp || f <= a || a + k >= next)
		{
			return ca;
		}
		Decoded db = decoded(groupOf(a + k));
		List<Object[]> cb = db.calls.get(a + k);
		if (cb == null)
		{
			return ca;
		}
		return Interp.between(ca, da.ids.get(a), cb, db.ids.get(a + k), (f - a) / (double) k);
	}

	// ---- 2D layer for playback ----

	private UiCodec.Decoder udec;
	private int udecF = -1;

	/** The 2D layer playback shows at the i-th kept frame, full size (reused between calls), or null. */
	synchronized int[] ui(int i)
	{
		int f = base + i;
		if (f < base || f >= next)
		{
			return null;
		}
		if (udec != null && udecF == f)
		{
			return lastUi;
		}
		if (udec != null && f > udecF && f - udecF <= 2 * uiOpts.key)
		{
			// playback skipped a few frames: decode on through them
			for (int c = udecF + 1; c <= f; c++)
			{
				decodeUi(c);
			}
			return lastUi;
		}
		if (udec == null || udecF != f - 1)
		{
			// a seek: from the last frame standing alone, through the checkpoints, to f
			int start = f;
			while (start > base && (uiFlags(start) & 2) == 0)
			{
				start--;
			}
			if ((uiFlags(start) & 2) == 0)
			{
				return null;
			}
			udec = new UiCodec.Decoder(uiOpts);
			int lastKey = start;
			for (int c = start + 1; c <= f; c++)
			{
				if ((uiFlags(c) & 1) != 0)
				{
					lastKey = c;
				}
			}
			decodeUi(start);
			for (int c = start + 1; c <= lastKey; c++)
			{
				if ((uiFlags(c) & 1) != 0)
				{
					decodeUi(c);
				}
			}
			for (int c = lastKey + 1; c <= f; c++)
			{
				decodeUi(c);
			}
			return lastUi;
		}
		decodeUi(f);
		return lastUi;
	}

	private int[] lastUi, uiOut;

	private int uiFlags(int f)
	{
		Group g = groupOf(f);
		return meta(g).uiFlags[f - g.first];
	}

	private void decodeUi(int f)
	{
		Group g = groupOf(f);
		Meta m = meta(g);
		int k = f - g.first;
		byte[] rec = g.closed() ? Arrays.copyOfRange(g.uiAll, m.uiOff[k], m.uiOff[k + 1]) : g.ui[k];
		if ((m.uiFlags[k] & 4) == 0)
		{
			udecF = f;
			return;
		}
		int[] layer = udec.decode(rec, f, m.yaw[k], m.uiN[k], m.uiWidth[k]);
		if (layer != null && layer.length != m.uiN[k])
		{
			// hand back an array as long as the client's, which can have a pixel past the last row
			if (uiOut == null || uiOut.length != m.uiN[k])
			{
				uiOut = new int[m.uiN[k]];
			}
			System.arraycopy(layer, 0, uiOut, 0, Math.min(layer.length, uiOut.length));
			layer = uiOut;
		}
		lastUi = layer;
		udecF = f;
	}

	/** Width of the i-th kept frame's 2D layer, or 0. */
	synchronized int uiWidth(int i)
	{
		int f = base + i;
		Group g = groupOf(f);
		return meta(g).uiWidth[f - g.first];
	}

	synchronized int modelCount()
	{
		return models.size();
	}
}
