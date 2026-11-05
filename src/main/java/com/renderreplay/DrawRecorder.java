package com.renderreplay;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Wraps the client's DrawCallbacks (normally the GPU plugin). While recording, every call is logged and forwarded
 * untouched; while playing, the live world's calls are held back and the recorded ones are issued in their place.
 * Calls are buffered per frame and written as one line when the renderer calls draw(int), which ends the frame.
 * Frames are also retained in memory, under a byte budget, as the playback source.
 */
@Slf4j
final class DrawRecorder implements DrawCallbacks
{
	// A frame with more calls than this is truncated in the draws file.
	private static final int MAX_CALLS_PER_FRAME = 200_000;
	// Calls that draw the live world; held back and replaced by recorded ones during playback.
	private static final Set<String> WORLD_CALLS = new HashSet<>(Arrays.asList(
		"drawScene", "preSceneDraw", "postSceneDraw", "postDrawScene", "drawScenePaint", "drawSceneTileModel",
		"drawDynamic", "drawTemp", "drawPass", "drawZoneOpaque", "drawZoneAlpha", "draw"));
	private static final int RETAIN_FRAMES = 60_000;
	// Every Nth frame's 2D layer is stored whole, so dropping old frames never breaks the delta chain.
	private static final int UI_KEYFRAME_EVERY = 30;
	// Retained frames are kept while their estimated size fits this budget, but never fewer than minFrames.
	static volatile long budgetBytes = Math.min(Runtime.getRuntime().maxMemory() / 4, 1_500_000_000L);
	static volatile int minFrames = 300;

	private final Gson gson;
	private final DrawCallbacks delegate;
	private final BufferedWriter out;
	private final BufferedWriter modelsOut;
	// Makes a frozen copy of a live model: the client rewrites and reuses its Model objects. May be null.
	private final UnaryOperator<Model> snapshotter;
	private final Map<Integer, Model> snapshots = new HashMap<>();
	// Content hash -> id: each distinct geometry is written once to modelsOut and referenced by id.
	private final Map<Long, Integer> geometryIds = new HashMap<>();
	// drawDynamic's worker index is replayed round-robin over the number of workers seen while recording.
	private long dynCounter;
	private volatile int dynWorkers = 3;

	/** Live client state the recorder reads; implemented by the plugin. */
	interface Probe
	{
		/** The client's 2D layer (interface and overlays) as ARGB pixels, or null. */
		int[] uiPixels();

		int scale();

		int tick();

		/** Local player position {x, z} in scene units, or null. */
		float[] playerLocal();

		default Object liveScene()
		{
			return null;
		}

		default Object liveWorldView()
		{
			return null;
		}

		/** The client's game cycle (20 ms steps), or -1. */
		default int gameCycle()
		{
			return -1;
		}

		/** Runs r on the client thread: right away when already on it, else queued. */
		default void onClientThread(Runnable r)
		{
			r.run();
		}
	}

	/** One recorded frame, kept with its real argument objects so it can be re-issued to the renderer. */
	private static final class RawFrame
	{
		final long wallMs;
		final List<Object[]> calls; // {Call, Object[] args}
		final byte[] uiDelta;       // 2D layer, delta against the previous frame (whole when keyframe)
		final boolean keyframe;
		final int scale;
		final float playerX, playerZ; // NaN when unknown
		final long bytes;             // rough retained size
		final int tick;
		final int cycle;              // client game cycle when recorded, or -1
		final Scene scene;            // the scene this frame's world calls were issued for, or null

		RawFrame(long wallMs, List<Object[]> calls, byte[] uiDelta, boolean keyframe, int scale, float[] player, int tick, int cycle)
		{
			Scene sc = null;
			for (Object[] c : calls)
			{
				Object[] args = (Object[]) c[1];
				for (int i = 0; args != null && sc == null && i < args.length; i++)
				{
					if (args[i] instanceof Scene)
					{
						sc = (Scene) args[i];
					}
				}
				if (sc != null)
				{
					break;
				}
			}
			this.scene = sc;
			this.wallMs = wallMs;
			this.calls = calls;
			this.uiDelta = uiDelta;
			this.keyframe = keyframe;
			this.scale = scale;
			this.playerX = player == null ? Float.NaN : player[0];
			this.playerZ = player == null ? Float.NaN : player[1];
			this.tick = tick;
			this.cycle = cycle;
			this.bytes = calls.size() * 160L + (uiDelta == null ? 0 : uiDelta.length) + 512;
		}
	}

	private final Probe probe;
	private int[] prevUi;
	private long uiFrameCounter;
	// Playback: the reconstructed 2D layer and which recorded frame it holds.
	private int[] uiBuf;
	private int uiAppliedIdx = -1;
	private volatile List<RawFrame> playFrames = new ArrayList<>();
	// While >= 0, playback is frozen on this frame.
	private volatile int holdIndex = -1;
	// While >= 0, playback runs in real time and freezes on this frame when it reaches it (a tick step).
	private volatile int pauseAtIdx = -1;

	private List<Object[]> rawCalls = new ArrayList<>();
	private final List<RawFrame> retained = new ArrayList<>();
	private long retainedBytes;
	private volatile boolean playing;
	// False once recording has stopped: later frames are forwarded but not retained.
	private volatile boolean capturing = true;
	private volatile long playStartMs;
	// Set when real-time playback (re)starts: the next frame aligns the texture animation phase.
	private volatile boolean alignPending;
	private volatile Runnable onPlaybackEnd;
	private volatile int lastReplayIndex = -1;

	private final AtomicInteger nextId = new AtomicInteger();
	private final Map<Scene, Integer> sceneIds = new IdentityHashMap<>();
	private final Map<Integer, Scene> sceneObjs = new HashMap<>();
	private final Map<Integer, int[]> sceneBase = new HashMap<>();
	private final Object lock = new Object();
	private List<Object[]> calls = new ArrayList<>();
	private long dropped;
	private long frame;

	// The GPU plugin animates textures from gameCycle & 127.
	static final int TEXTURE_PERIOD = 128;
	static final int CYCLE_MS = 20;

	/**
	 * How far to move the playback clock so the recorded frames' texture phase equals the live one: the GPU plugin
	 * animates water and lava from the live game cycle. Positive skips ahead in the recording, negative shows the first
	 * frame that much longer; at most half a period either way.
	 */
	static long phaseShiftMs(int liveCycle, int recordedCycle)
	{
		int d = Math.floorMod(liveCycle - recordedCycle, TEXTURE_PERIOD);
		return (d <= TEXTURE_PERIOD / 2 ? d : d - TEXTURE_PERIOD) * (long) CYCLE_MS;
	}

	private DrawRecorder(DrawCallbacks delegate, Gson gson, BufferedWriter out, BufferedWriter modelsOut, UnaryOperator<Model> snapshotter, Probe probe)
	{
		this.delegate = delegate;
		this.gson = gson;
		this.out = out;
		this.modelsOut = modelsOut;
		this.snapshotter = snapshotter;
		this.probe = probe;
	}

	static DrawCallbacks wrap(DrawCallbacks delegate, Gson gson, BufferedWriter out, BufferedWriter modelsOut, UnaryOperator<Model> snapshotter, Probe probe)
	{
		return new DrawRecorder(delegate, gson, out, modelsOut, snapshotter, probe);
	}

	/**
	 * Builds a playback-only recording from a parsed .rrec file: every recorded call becomes a real call on the delegate,
	 * with FileReplay stand-ins for models, objects and projections and the live scene in place of the recorded one.
	 */
	static DrawCallbacks fromFile(DrawCallbacks delegate, Probe probe, RecordingFile rf)
	{
		DrawRecorder r = new DrawRecorder(delegate, null, null, null, null, probe);
		r.capturing = false;
		r.importedBase = new int[]{rf.baseX, rf.baseY};
		Map<String, Object> cache = new HashMap<>();
		int off = rf.drawFrames.size() - rf.frames.size();
		for (int k = 0; k < rf.frames.size(); k++)
		{
			RecordingFile.Frame f = rf.frames.get(k);
			List<Object[]> calls = new ArrayList<>();
			for (com.google.gson.JsonElement e : rf.drawFrames.get(off + k))
			{
				com.google.gson.JsonArray row = e.getAsJsonArray();
				Call m = Call.find(row.get(0).getAsString(), row.size() - 1);
				if (m == null || !WORLD_CALLS.contains(m.methodName) || m.isFrameEnd())
				{
					continue;
				}
				Class<?>[] pt = m.types;
				Object[] args = new Object[pt.length];
				for (int i = 0; i < pt.length; i++)
				{
					args[i] = importArg(row.get(i + 1), pt[i], rf, cache);
				}
				calls.add(new Object[]{m, args});
			}
			float[] player = Float.isNaN(f.playerX) ? null : new float[]{f.playerX, f.playerZ};
			synchronized (r.lock)
			{
				r.retained.add(new RawFrame(f.wallMs, calls, f.uiDelta, f.keyframe, f.scale, player, f.tick, -1));
			}
		}
		return r;
	}

	private static Object importArg(com.google.gson.JsonElement cell, Class<?> type, RecordingFile rf, Map<String, Object> cache)
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
			com.google.gson.JsonObject o = cell.getAsJsonObject();
			if (o.has("scene"))
			{
				return LIVE_SCENE;
			}
			else if (o.has("model"))
			{
				com.google.gson.JsonObject b = rf.models.get(o.get("model").getAsInt());
				made = b == null ? null : FileReplay.model(b);
			}
			else if (o.has("tilemodel"))
			{
				com.google.gson.JsonObject b = rf.models.get(o.get("tilemodel").getAsInt());
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
				made = o.has("s") ? FileReplay.projection(o) : null;
			}
			else if (o.has("set"))
			{
				Set<Integer> set = new HashSet<>();
				for (com.google.gson.JsonElement x : o.getAsJsonArray("set"))
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

	static DrawCallbacks unwrap(DrawCallbacks cb)
	{
		if (cb instanceof DrawRecorder)
		{
			return ((DrawRecorder) cb).delegate;
		}
		return cb;
	}

	static boolean isWrapped(DrawCallbacks cb)
	{
		return cb instanceof DrawRecorder;
	}

	// ---- DrawCallbacks ----

	@Override
	public void draw(net.runelite.api.Projection p, Scene sc, net.runelite.api.Renderable r, int a, int b, int c, int d, long e)
	{
		handle(Call.DRAW_PROJ, new Object[]{p, sc, r, a, b, c, d, e});
	}

	@Override
	public void drawScenePaint(Scene sc, SceneTilePaint p, int a, int b, int c)
	{
		handle(Call.DRAW_SCENE_PAINT, new Object[]{sc, p, a, b, c});
	}

	@Override
	public void drawSceneTileModel(Scene sc, SceneTileModel t, int a, int b)
	{
		handle(Call.DRAW_SCENE_TILE_MODEL, new Object[]{sc, t, a, b});
	}

	@Override
	public void draw(int overlayColor)
	{
		handle(Call.DRAW_FRAME_END, new Object[]{overlayColor});
	}

	@Override
	public void drawScene(double x, double y, double z, double pitch, double yaw, int plane)
	{
		handle(Call.DRAW_SCENE, new Object[]{x, y, z, pitch, yaw, plane});
	}

	@Override
	public void postDrawScene()
	{
		handle(Call.POST_DRAW_SCENE, new Object[0]);
	}

	@Override
	public void animate(net.runelite.api.Texture t, int diff)
	{
		handle(Call.ANIMATE, new Object[]{t, diff});
	}

	@Override
	public void loadScene(Scene sc)
	{
		handle(Call.LOAD_SCENE, new Object[]{sc});
	}

	@Override
	public void swapScene(Scene sc)
	{
		handle(Call.SWAP_SCENE, new Object[]{sc});
	}

	@Override
	public boolean tileInFrustum(Scene sc, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX, int cameraY, int cameraZ, int x, int y, int z)
	{
		return Boolean.TRUE.equals(handle(Call.TILE_IN_FRUSTUM, new Object[]{sc, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, x, y, z}));
	}

	@Override
	public boolean zoneInFrustum(int zx, int zz, int a, int b)
	{
		return Boolean.TRUE.equals(handle(Call.ZONE_IN_FRUSTUM, new Object[]{zx, zz, a, b}));
	}

	@Override
	public void loadScene(net.runelite.api.WorldView wv, Scene sc)
	{
		handle(Call.LOAD_SCENE_WV, new Object[]{wv, sc});
	}

	@Override
	public void despawnWorldView(net.runelite.api.WorldView wv)
	{
		handle(Call.DESPAWN_WORLD_VIEW, new Object[]{wv});
	}

	@Override
	public void preSceneDraw(Scene sc, net.runelite.api.Projection p, float x, float y, float z, float pitch, float yaw, int a, int b, int c, Set<Integer> s)
	{
		handle(Call.PRE_SCENE_DRAW_PROJ, new Object[]{sc, p, x, y, z, pitch, yaw, a, b, c, s});
	}

	@Override
	public void preSceneDraw(Scene sc, float x, float y, float z, float pitch, float yaw, int a, int b, int c, Set<Integer> s)
	{
		handle(Call.PRE_SCENE_DRAW, new Object[]{sc, x, y, z, pitch, yaw, a, b, c, s});
	}

	@Override
	public void postSceneDraw(Scene sc)
	{
		handle(Call.POST_SCENE_DRAW, new Object[]{sc});
	}

	@Override
	public void drawPass(net.runelite.api.Projection p, Scene sc, int pass)
	{
		handle(Call.DRAW_PASS, new Object[]{p, sc, pass});
	}

	@Override
	public void drawZoneOpaque(net.runelite.api.Projection p, Scene sc, int zx, int zz)
	{
		handle(Call.DRAW_ZONE_OPAQUE, new Object[]{p, sc, zx, zz});
	}

	@Override
	public void drawZoneAlpha(net.runelite.api.Projection p, Scene sc, int level, int zx, int zz)
	{
		handle(Call.DRAW_ZONE_ALPHA, new Object[]{p, sc, level, zx, zz});
	}

	@Override
	public void drawDynamic(net.runelite.api.Projection p, Scene sc, TileObject o, net.runelite.api.Renderable r, Model m, int a, int b, int c, int d)
	{
		handle(Call.DRAW_DYNAMIC, new Object[]{p, sc, o, r, m, a, b, c, d});
	}

	@Override
	public void drawDynamic(int worker, net.runelite.api.Projection p, Scene sc, TileObject o, net.runelite.api.Renderable r, Model m, int a, int b, int c, int d)
	{
		handle(Call.DRAW_DYNAMIC_WORKER, new Object[]{worker, p, sc, o, r, m, a, b, c, d});
	}

	@Override
	public void drawTemp(net.runelite.api.Projection p, Scene sc, GameObject go, Model m, int a, int b, int c, int d)
	{
		handle(Call.DRAW_TEMP, new Object[]{p, sc, go, m, a, b, c, d});
	}

	@Override
	public void invalidateZone(Scene sc, int zx, int zz)
	{
		handle(Call.INVALIDATE_ZONE, new Object[]{sc, zx, zz});
	}

	// Copies of the scenes the client loaded while a recorder was installed; shared by all recorders.
	static final SceneKeeper KEEPER = new SceneKeeper();

	private Object handle(Call m, Object[] args)
	{
		if (m.isSceneChange())
		{
			return sceneChange(m, args);
		}
		boolean frameEnd = m.isFrameEnd();
		if (playing)
		{
			if (m == Call.DRAW_SCENE)
			{
				replayFrame();
				return null;
			}
			if (WORLD_CALLS.contains(m.methodName) && !frameEnd)
			{
				return null;
			}
			if (frameEnd)
			{
				try
				{
					applyUi();
				}
				catch (RuntimeException e)
				{
					log.debug("could not draw the recorded 2D layer", e);
				}
			}
			return m.invoke(delegate, args);
		}
		if (!capturing)
		{
			return m.invoke(delegate, args);
		}

		// Record before delegating, so the file shows what the renderer was handed even if it throws.
		try
		{
			record(m, args);
		}
		catch (RuntimeException e)
		{
			log.debug("could not record {}", m.methodName, e);
		}
		byte[] ui = null;
		boolean key = false;
		if (frameEnd)
		{
			key = uiFrameCounter++ % UI_KEYFRAME_EVERY == 0;
			ui = captureUi(key);
		}
		Object r = m.invoke(delegate, args);
		if (frameEnd)
		{
			flush(args[0], ui, key);
		}
		return r;
	}

	private void record(Call m, Object[] args)
	{
		if (args != null && (m.methodName.equals("loadScene") || m.methodName.equals("swapScene")))
		{
			synchronized (sceneIds)
			{
				for (Object a : args)
				{
					sceneIds.remove(a);
				}
			}
		}
		Object[] row = new Object[1 + (args == null ? 0 : args.length)];
		row[0] = m.methodName;
		if (args != null)
		{
			for (int i = 0; i < args.length; i++)
			{
				row[i + 1] = describe(args[i]);
			}
		}
		if (args != null && args.length > 0 && args[0] instanceof Integer && m.methodName.equals("drawDynamic")
			&& (Integer) args[0] >= dynWorkers && (Integer) args[0] < 64)
		{
			dynWorkers = (Integer) args[0] + 1;   // learn how many workers the client dispatched to
		}
		synchronized (lock)
		{
			if (WORLD_CALLS.contains(m.methodName) && !m.isFrameEnd())
			{
				rawCalls.add(new Object[]{m, frozen(m, args, row)});
			}
			if (calls.size() < MAX_CALLS_PER_FRAME)
			{
				calls.add(row);
			}
			else
			{
				dropped++;
			}
		}
	}

	private void flush(Object overlayColor, byte[] ui, boolean key)
	{
		List<Object[]> batch;
		long drop;
		synchronized (lock)
		{
			retained.add(new RawFrame(System.currentTimeMillis(), rawCalls, ui, key, probe == null ? 0 : probe.scale(),
				probe == null ? null : probe.playerLocal(), probe == null ? 0 : probe.tick(), probe == null ? -1 : probe.gameCycle()));
			retainedBytes += retained.get(retained.size() - 1).bytes;
			// Drop the oldest frames while over budget, but only down to a keyframe so the 2D-layer chain stays decodable.
			while ((retainedBytes > budgetBytes || retained.size() > RETAIN_FRAMES) && retained.size() > minFrames
				&& !retained.get(1).keyframe)
			{
				retainedBytes -= retained.remove(0).bytes;
			}
			if ((retainedBytes > budgetBytes || retained.size() > RETAIN_FRAMES) && retained.size() > minFrames)
			{
				retainedBytes -= retained.remove(0).bytes;
			}
			rawCalls = new ArrayList<>();
			projCache.clear();
			batch = calls;
			calls = new ArrayList<>();
			drop = dropped;
			dropped = 0;
		}
		final long wall = System.currentTimeMillis();
		final long fr = frame++;
		// Serialising, delta-encoding and writing happen on the writer thread, not the render thread.
		writer.execute(() -> writeFrame(batch, fr, wall, overlayColor, drop));
	}

	private void writeFrame(List<Object[]> batch, long fr, long wall, Object overlayColor, long drop)
	{
		List<String> rows = new ArrayList<>(batch.size());
		for (Object[] r : batch)
		{
			rows.add(gson.toJson(r));
		}
		String text = "{\"frame\":" + fr + ",\"wallMs\":" + wall + ",\"overlay\":" + gson.toJson(overlayColor)
			+ ",\"dropped\":" + drop + ",\"enc\":\"d1\",\"calls\":" + delta.encode(rows) + "}";
		try
		{
			synchronized (out)
			{
				out.write(text);
				out.newLine();
				out.flush();
			}
		}
		catch (IOException e)
		{
			log.debug("could not write a frame", e);
		}
	}

	// Bounded queue: when the writer falls behind, the render thread writes the frame itself.
	private final java.util.concurrent.ExecutorService writer = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
		java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(1500), r ->
		{
			Thread t = new Thread(r, "render-replay-writer");
			t.setDaemon(true);
			return t;
		}, new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
	private final DrawDelta delta = new DrawDelta();

	/** Waits until every frame handed to the writer thread has been written and stops the thread. Call before closing the files. */
	static void drainWrites(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		r.writer.shutdown();
		try
		{
			r.writer.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/** Compact description of an argument for the draws file. Never calls anything that could compute a model. */
	private Object describe(Object a)
	{
		if (a == null || a instanceof Number || a instanceof Boolean || a instanceof String)
		{
			return a;
		}
		if (a instanceof Model)
		{
			return geometry("model", (Model) a);
		}
		if (a instanceof Scene)
		{
			return sceneRef((Scene) a);
		}
		if (a instanceof SceneTileModel)
		{
			return geometry("tilemodel", (SceneTileModel) a);
		}
		if (a instanceof SceneTilePaint)
		{
			SceneTilePaint t = (SceneTilePaint) a;
			return new int[]{t.getRBG(), t.getSwColor(), t.getSeColor(), t.getNwColor(), t.getNeColor(),
				t.getTexture(), t.isFlat() ? 1 : 0};
		}
		if (a instanceof GameObject)
		{
			return describeGameObject((GameObject) a);
		}
		if (a instanceof TileObject)
		{
			TileObject t = (TileObject) a;
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("tobj", t.getId());
			o.put("plane", t.getPlane());
			o.put("hash", t.getHash());
			return o;
		}
		if (a instanceof net.runelite.api.Renderable)
		{
			net.runelite.api.Renderable r = (net.runelite.api.Renderable) a;
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("rend", a instanceof NPC ? "NPC{" + ((NPC) a).getId() + "}" : a instanceof Player ? "Player" : "Renderable");
			try
			{
				o.put("h", r.getModelHeight());
				o.put("m", r.getRenderMode());
			}
			catch (RuntimeException e)
			{
				o.put("err", String.valueOf(e));
			}
			return o;
		}
		if (a instanceof WorldView)
		{
			return "WorldView{" + ((WorldView) a).getId() + "}";
		}
		if (a instanceof Set)
		{
			return java.util.Collections.singletonMap("set", new ArrayList<Object>((Set<?>) a));
		}
		if (a instanceof net.runelite.api.Projection)
		{
			// the GPU plugin calls project(x,y,z) on it: keep the matrix if there is one, else 4 sample points (an affine map)
			net.runelite.api.Projection pr = (net.runelite.api.Projection) a;
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("proj", a instanceof net.runelite.api.FloatProjection ? "float" : "other");
			try
			{
				if (a instanceof net.runelite.api.FloatProjection)
				{
					o.put("m", ((net.runelite.api.FloatProjection) a).getProjection());
				}
				o.put("s", new float[][]{pr.project(0, 0, 0).clone(), pr.project(128, 0, 0).clone(), pr.project(0, 128, 0).clone(), pr.project(0, 0, 128).clone()});
			}
			catch (RuntimeException e)
			{
				o.put("err", String.valueOf(e));
			}
			return o;
		}
		return "object";
	}

	/** Everything drawTemp/drawDynamic read from a GameObject wrapper, captured now (the client reuses the wrapper). */
	private static Object describeGameObject(GameObject go)
	{
		Map<String, Object> o = new LinkedHashMap<>();
		try
		{
			o.put("go", go.getId());
			o.put("hash", go.getHash());
			o.put("xyz", new int[]{go.getX(), go.getY(), go.getZ()});
			o.put("plane", go.getPlane());
			o.put("ori", new int[]{go.getOrientation(), go.getModelOrientation(), go.getConfig()});
			o.put("size", new int[]{go.sizeX(), go.sizeY()});
			net.runelite.api.Point mn = go.getSceneMinLocation(), mx = go.getSceneMaxLocation();
			o.put("box", new int[]{mn.getX(), mn.getY(), mx.getX(), mx.getY()});
			net.runelite.api.Renderable r = go.getRenderable();
			if (r != null)
			{
				o.put("h", r.getModelHeight());
				o.put("m", r.getRenderMode());
			}
		}
		catch (RuntimeException e)
		{
			o.put("err", String.valueOf(e));
		}
		return o;
	}

	/** Returns {"model": id}; writes the geometry to modelsOut the first time this exact content is seen. */
	private Object geometry(String kind, Object g)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		if (g instanceof Model)
		{
			Model m = (Model) g;
			// The API hands back oversized backing arrays; only the first vn / fn entries are real.
			int vn = m.getVerticesCount(), fn = m.getFaceCount();
			body.put("vx", trim(m.getVerticesX(), vn));
			body.put("vy", trim(m.getVerticesY(), vn));
			body.put("vz", trim(m.getVerticesZ(), vn));
			body.put("f1", trim(m.getFaceIndices1(), fn));
			body.put("f2", trim(m.getFaceIndices2(), fn));
			body.put("f3", trim(m.getFaceIndices3(), fn));
			body.put("c1", trim(m.getFaceColors1(), fn));
			body.put("c2", trim(m.getFaceColors2(), fn));
			body.put("c3", trim(m.getFaceColors3(), fn));
			body.put("vn", vn);
			body.put("fn", fn);
			body.put("tr", trim(m.getFaceTransparencies(), fn));
			body.put("pri", trim(m.getFaceRenderPriorities(), fn));
			// Format v2 extras: the rest of what the GPU plugin reads from a Model, so a file can rebuild a stand-in model.
			try
			{
				body.put("ft", trim(m.getFaceTextures(), fn));
				body.put("fb", trim(m.getFaceBias(), fn));
				body.put("ov", new int[]{m.getOverrideAmount(), m.getOverrideHue(), m.getOverrideSaturation(), m.getOverrideLuminance()});
				body.put("tp", (int) m.getTransparency());
				byte[] tf = m.getTextureFaces();
				body.put("tf", tf);
				body.put("t1", m.getTexIndices1());
				body.put("t2", m.getTexIndices2());
				body.put("t3", m.getTexIndices3());
				body.put("rad", m.getRadius());
				body.put("dia", m.getDiameter());
			}
			catch (RuntimeException e)
			{
				body.put("v2err", String.valueOf(e));
			}
		}
		else
		{
			SceneTileModel t = (SceneTileModel) g;
			body.put("vx", t.getVertexX());
			body.put("vy", t.getVertexY());
			body.put("vz", t.getVertexZ());
			body.put("f1", t.getFaceX());
			body.put("f2", t.getFaceY());
			body.put("f3", t.getFaceZ());
			body.put("c1", t.getTriangleColorA());
			body.put("c2", t.getTriangleColorB());
			body.put("c3", t.getTriangleColorC());
			body.put("tex", t.getTriangleTextureId());
			body.put("flat", t.isFlat());
		}
		long h = kind.hashCode();
		for (Object v : body.values())
		{
			h = h * 1099511628211L + hashValue(v);
		}
		int id;
		synchronized (geometryIds)
		{
			Integer known = geometryIds.get(h);
			if (known != null)
			{
				return java.util.Collections.singletonMap(kind, known);
			}
			id = nextId.getAndIncrement();
			geometryIds.put(h, id);
		}
		if (g instanceof Model && snapshotter != null)
		{
			try
			{
				Model copy = snapshotter.apply((Model) g);
				synchronized (snapshots)
				{
					snapshots.put(id, copy);
				}
			}
			catch (RuntimeException e)
			{
				// no snapshot: playback falls back to the live object for this model
			}
		}
		body.put("id", id);
		body.put("kind", kind);
		try
		{
			synchronized (modelsOut)
			{
				modelsOut.write(gson.toJson(body));
				modelsOut.newLine();
				modelsOut.flush();
			}
		}
		catch (IOException e)
		{
			// keep rendering
		}
		return java.util.Collections.singletonMap(kind, id);
	}

	private static long hashValue(Object v)
	{
		if (v instanceof int[])
		{
			return Arrays.hashCode((int[]) v) * 31L + ((int[]) v).length;
		}
		if (v instanceof float[])
		{
			return Arrays.hashCode((float[]) v) * 31L + ((float[]) v).length;
		}
		if (v instanceof short[])
		{
			return Arrays.hashCode((short[]) v);
		}
		if (v instanceof byte[])
		{
			return Arrays.hashCode((byte[]) v);
		}
		return v == null ? 0 : v.hashCode();
	}

	private static int[] trim(int[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	private static float[] trim(float[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	private static short[] trim(short[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	private static byte[] trim(byte[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	/** Returns {"scene": id}; writes the scene's base to modelsOut the first time the scene is seen. */
	private Object sceneRef(Scene sc)
	{
		int id;
		synchronized (sceneIds)
		{
			Integer known = sceneIds.get(sc);
			if (known != null)
			{
				return java.util.Collections.singletonMap("scene", known);
			}
			id = nextId.getAndIncrement();
			sceneIds.put(sc, id);
			sceneObjs.put(id, sc);
			sceneBase.put(id, new int[]{sc.getBaseX(), sc.getBaseY()});
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", id);
		body.put("kind", "scene");
		body.put("worldView", sc.getWorldViewId());
		body.put("baseX", sc.getBaseX());
		body.put("baseY", sc.getBaseY());
		try
		{
			synchronized (modelsOut)
			{
				modelsOut.write(gson.toJson(body));
				modelsOut.newLine();
				modelsOut.flush();
			}
		}
		catch (IOException e)
		{
			// keep rendering
		}
		return java.util.Collections.singletonMap("scene", id);
	}

	// ---- playback ----

	/** Shifts the playback clock once so the recorded frames' texture phase equals the live one (see phaseShiftMs). */
	private void alignPhase(List<RawFrame> frames)
	{
		if (probe == null || pauseAtIdx >= 0)
		{
			return;
		}
		int live = probe.gameCycle();
		long elapsed = System.currentTimeMillis() - playStartMs;
		long base = frames.get(0).wallMs;
		RawFrame f = frames.get(0);
		for (RawFrame c : frames)
		{
			if (c.wallMs - base > elapsed)
			{
				break;
			}
			f = c;
		}
		if (live < 0 || f.cycle < 0)
		{
			return; // unknown (imported recording): leave the clock alone
		}
		long shift = phaseShiftMs(live, f.cycle);
		playStartMs -= shift;
	}

	private void replayFrame()
	{
		List<RawFrame> frames;
		synchronized (lock)
		{
			frames = new ArrayList<>(retained);
		}
		if (frames.isEmpty() || endRequested)
		{
			endPlayback();
			return;
		}
		playFrames = frames;
		RawFrame f;
		int idx = 0;
		if (holdIndex >= 0)
		{
			// frozen on one frame
			idx = Math.min(holdIndex, frames.size() - 1);
			f = frames.get(idx);
		}
		else
		{
			if (alignPending)
			{
				alignPending = false;
				alignPhase(frames);
			}
			long elapsed = System.currentTimeMillis() - playStartMs;
			long base = frames.get(0).wallMs;
			if (elapsed > frames.get(frames.size() - 1).wallMs - base)
			{
				endPlayback();
				return;
			}
			f = frames.get(0);
			int i = 0;
			for (RawFrame c : frames)
			{
				if (c.wallMs - base > elapsed)
				{
					break;
				}
				f = c;
				idx = i++;
			}
			int stop = pauseAtIdx;
			if (stop >= 0 && idx >= stop)
			{
				// the tick step has played through: freeze on the first frame of the next tick
				idx = Math.min(stop, frames.size() - 1);
				f = frames.get(idx);
				holdIndex = idx;
				pauseAtIdx = -1;
			}
		}
		lastReplayIndex = idx;
		ensureScene(f);
		int liveScale = probe == null ? 0 : probe.scale();
		for (Object[] c : f.calls)
		{
			Call cm = (Call) c[0];
			Object[] callArgs = resolveLive(dynIndex(cm, compensateZoom(cm, (Object[]) c[1], f, liveScale)));
			try
			{
				cm.invoke(delegate, callArgs);
			}
			catch (RuntimeException | Error e)
			{
				// A bad replayed call must not take the client down.
				log.debug("replayed {} failed", cm.methodName, e);
			}
		}
	}

	// ---- multi-scene playback ----
	// The GPU plugin draws terrain from the zones of the scene it has loaded. A recording made across a map change holds
	// frames of several scenes, so before replaying a frame of another scene the GPU plugin is made to load a copy of it
	// with the calls the client itself uses (loadScene, loadScene(worldView, scene), swapScene). The live scene is put
	// back when playback ends.
	//
	// The client prepares a new scene in loadScene(worldView, scene) on its map-loading thread and installs it in
	// swapScene(scene) on the client thread; in between, the prepared zones wait inside the GPU plugin. So:
	//  - nothing here may throw out of the client's own scene calls;
	//  - while a live load is pending, no swap of ours runs;
	//  - a live load that starts while a recorded scene is loaded first waits for the client thread to put the live
	//    scene back;
	//  - a renderer failure in these calls during playback is contained: the client's scene is reloaded and playback ends.
	private volatile Object sceneInUse;    // the top-level scene the GPU plugin holds now (a recorded one while swapped)
	private volatile boolean sceneSwapFailed;
	private volatile Object clientScene;   // the top-level scene the client itself last handed the GPU plugin
	private volatile boolean liveLoadPending;
	private volatile boolean endRequested; // playback must end at the next frame
	// How long the map-loading thread waits for the client thread to put the live scene back.
	static volatile long loadGuardTimeoutMs = 2000;

	private Object sceneChange(Call m, Object[] args)
	{
		Scene sc = null;
		boolean top = false;
		try
		{
			Object a = m == Call.LOAD_SCENE_WV ? args[1] : m == Call.DESPAWN_WORLD_VIEW ? null : args[0];
			sc = a instanceof Scene ? (Scene) a : null;
			top = sc != null && sc.getWorldViewId() == 0;
		}
		catch (Throwable t)
		{
			log.debug("{} bookkeeping failed", m.methodName, t);
		}
		try
		{
			if (m == Call.INVALIDATE_ZONE && sc != null)
			{
				KEEPER.refreshZone(sc, ((Number) args[1]).intValue(), ((Number) args[2]).intValue());
			}
		}
		catch (Throwable t)
		{
			log.debug("{} bookkeeping failed", m.methodName, t);
		}
		try
		{
			if (top && m == Call.LOAD_SCENE_WV)
			{
				beforeLiveLoad();
			}
			if (capturing && !playing)
			{
				record(m, args);
			}
		}
		catch (Throwable t)
		{
			log.debug("{} bookkeeping failed", m.methodName, t);
		}
		if (playing && m == Call.INVALIDATE_ZONE && sceneInUse != null && sceneInUse != clientScene)
		{
			return null; // the GPU plugin holds a recorded scene: a live zone invalidation would rebuild it from the live scene
		}
		try
		{
			Object r = m.invoke(delegate, args);
			if (top && m == Call.SWAP_SCENE)
			{
				clientScene = sc;
				sceneInUse = sc;
				liveLoadPending = false;
			}
			return r;
		}
		catch (RuntimeException | Error t)
		{
			log.debug("{} bookkeeping failed", m.methodName, t);
			if (!playing)
			{
				throw t; // not caused by a replay: the client sees its renderer's error as it would without this plugin
			}
			recover(m, sc, top);
			return null;
		}
		finally
		{
			if (m == Call.LOAD_SCENE_WV && sc != null)
			{
				copyLoadedScene(sc);
			}
		}
	}

	/**
	 * Copies a scene right after the renderer's own loadScene: the GPU plugin's "hide unrelated maps" removes tiles from
	 * the scene there, and replay loads skip that step, so the copy must already be pruned.
	 */
	private void copyLoadedScene(Scene sc)
	{
		try
		{
			KEEPER.capture(sc);
		}
		catch (RuntimeException | Error t)
		{
			// A scene that cannot be copied cannot be replayed later; the client's load goes on.
			log.debug("could not copy the loaded scene", t);
		}
	}

	/** Map-loading thread, at a live top-level loadScene. */
	private void beforeLiveLoad()
	{
		liveLoadPending = true;
		// A swap of ours already running on the client thread finishes before this load reaches the renderer: switchScene
		// checks liveLoadPending after taking switchLock, and this thread takes the lock after raising liveLoadPending.
		try
		{
			if (switchLock.tryLock(loadGuardTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
			{
				switchLock.unlock();
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		if (!playing || probe == null || sceneInUse == null || sceneInUse == clientScene)
		{
			return;
		}
		java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		AtomicInteger phase = new AtomicInteger(); // 0 waiting, 1 running, 2 abandoned
		probe.onClientThread(() ->
		{
			if (!phase.compareAndSet(0, 1))
			{
				return;
			}
			try
			{
				putBackClientScene(true);
			}
			finally
			{
				done.countDown();
			}
		});
		try
		{
			if (!done.await(loadGuardTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
			{
				if (phase.compareAndSet(0, 2))
				{
					// the client thread never got to it: proceed, with our swaps off
					sceneSwapFailed = true;
					endRequested = true;
				}
				else
				{
					done.await(); // it is running right now: let it finish before the load continues
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}

	/** A scene call failed during playback: reload the client's scene from its copy and end playback. */
	private void recover(Call m, Scene sc, boolean top)
	{
		sceneSwapFailed = true;
		endRequested = true;
		if (top && m == Call.SWAP_SCENE && sc != null)
		{
			clientScene = sc;
			liveLoadPending = false;
			sceneInUse = null; // unknown after a failed swap: force the reload below
			sceneSwapFailed = false;
			switchScene(sc);
			sceneSwapFailed = true;
		}
	}

	private void ensureScene(RawFrame f)
	{
		if (sceneSwapFailed || importedBase != null || f.scene == null || probe == null || liveLoadPending)
		{
			return;
		}
		if (clientScene == null)
		{
			clientScene = probe.liveScene();
		}
		if (sceneInUse == null)
		{
			sceneInUse = clientScene;
		}
		if (f.scene == sceneInUse)
		{
			return;
		}
		if (probe.liveScene() != clientScene)
		{
			return; // the client changed scenes without this wrapper seeing it
		}
		if (!(clientScene instanceof Scene) || KEEPER.get(f.scene) == null || ((Scene) clientScene).isInstance())
		{
			return; // no copy to load, or the live scene is an instance: draw over the live one
		}
		if (sceneInUse == clientScene)
		{
			// Leaving the live scene: copy it now, so the restore shows the world as it is.
			try
			{
				KEEPER.capture((Scene) clientScene);
			}
			catch (RuntimeException e)
			{
				return;
			}
		}
		else if (KEEPER.get((Scene) clientScene) == null)
		{
			return; // no way back to the live scene
		}
		switchScene(f.scene);
	}

	// Held while a swap of ours runs.
	private final java.util.concurrent.locks.ReentrantLock switchLock = new java.util.concurrent.locks.ReentrantLock();

	private void switchScene(Scene target)
	{
		switchScene(target, false);
	}

	/** forLoadGuard: the map-loading thread is waiting for exactly this swap, so a pending load does not stop it. */
	private void switchScene(Scene target, boolean forLoadGuard)
	{
		switchLock.lock();
		try
		{
			if (liveLoadPending && !forLoadGuard)
			{
				return; // a live load started meanwhile: the renderer is the client's until its swapScene
			}
			switchSceneNow(target);
		}
		finally
		{
			switchLock.unlock();
		}
	}

	private void switchSceneNow(Scene target)
	{
		try
		{
			Object wv = probe.liveWorldView();
			Scene copy = KEEPER.get(target);
			if (copy == null)
			{
				return;
			}
			Object live = clientScene;
			Scene load = SceneKeeper.withoutZoneReuse((StandIns.StandInScene) copy, live instanceof Scene && ((Scene) live).isInstance());
			Call.LOAD_SCENE.invoke(delegate, new Object[]{load});
			if (wv instanceof net.runelite.api.WorldView)
			{
				Call.LOAD_SCENE_WV.invoke(delegate, new Object[]{wv, load});
			}
			Call.SWAP_SCENE.invoke(delegate, new Object[]{load});
			sceneInUse = target;
		}
		catch (RuntimeException | Error e)
		{
			sceneSwapFailed = true; // never retry a failing swap: fall back to drawing over the live scene
			log.warn("scene swap failed", e);
		}
	}

	/** Puts the client's own scene back into the GPU plugin if playback swapped another one in. */
	private void restoreLiveScene()
	{
		if (probe == null || sceneInUse == null || importedBase != null || liveLoadPending)
		{
			return;
		}
		putBackClientScene(false);
	}

	private void putBackClientScene(boolean forLoadGuard)
	{
		Object live = clientScene;
		if (live instanceof Scene && live != sceneInUse)
		{
			boolean was = sceneSwapFailed;
			sceneSwapFailed = false;
			switchScene((Scene) live, forLoadGuard);
			sceneSwapFailed = was || sceneSwapFailed;
		}
	}

	/** True when this recording can be played in another map area: recorded in this session and no swap has failed. */
	static boolean canSwapScenes(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		return !r.sceneSwapFailed && r.importedBase == null;
	}

	// Stands for "the scene loaded now" in frames rebuilt from a file.
	private static final Object LIVE_SCENE = new Object();
	private volatile int[] importedBase; // the recorded scene base {x, y} when this recording came from a file

	private Object[] resolveLive(Object[] args)
	{
		if (importedBase == null || args == null)
		{
			return args;
		}
		Object live = probe == null ? null : probe.liveScene();
		Object[] out = null;
		for (int i = 0; i < args.length; i++)
		{
			if (args[i] == LIVE_SCENE)
			{
				if (out == null)
				{
					out = args.clone();
				}
				out[i] = live;
			}
		}
		return out == null ? args : out;
	}

	private void endPlayback()
	{
		restoreLiveScene();
		playing = false;
		Runnable r = onPlaybackEnd;
		if (r != null)
		{
			r.run();
		}
	}

	private static DrawRecorder handler(DrawCallbacks cb)
	{
		return (DrawRecorder) cb;
	}

	/** {recorded base x, y, the recorded scene's base now x, y, live base x, y, 1 if the recorded scene is the live one}. */
	static int[] sceneInfo(DrawCallbacks cb, Scene live)
	{
		DrawRecorder r = handler(cb);
		int[] ib = r.importedBase;
		if (ib != null)
		{
			return live == null ? null : new int[]{ib[0], ib[1], ib[0], ib[1], live.getBaseX(), live.getBaseY(), 0};
		}
		synchronized (r.sceneIds)
		{
			if (r.sceneBase.isEmpty() || live == null)
			{
				return null;
			}
			int id = Collections.min(r.sceneBase.keySet());
			int[] rb = r.sceneBase.get(id);
			Scene o = r.sceneObjs.get(id);
			return new int[]{rb[0], rb[1], o.getBaseX(), o.getBaseY(), live.getBaseX(), live.getBaseY(), o == live ? 1 : 0};
		}
	}

	/** Local position {x, z} of the player in the first retained frame, or null. */
	static float[] firstPlayer(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		synchronized (r.lock)
		{
			if (r.retained.isEmpty() || Float.isNaN(r.retained.get(0).playerX))
			{
				return null;
			}
			return new float[]{r.retained.get(0).playerX, r.retained.get(0).playerZ};
		}
	}

	/** {positionMs, spanMs} of the retained recording for the panel's timeline slider; position is 0 unless playing. */
	static long[] timeline(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		long base, span, holdWall = -1;
		synchronized (r.lock)
		{
			if (r.retained.size() < 2)
			{
				return null;
			}
			base = r.retained.get(0).wallMs;
			span = r.retained.get(r.retained.size() - 1).wallMs - base;
			if (r.playing && r.holdIndex >= 0)
			{
				holdWall = r.retained.get(Math.min(r.holdIndex, r.retained.size() - 1)).wallMs - base;
			}
		}
		long pos = 0;
		if (r.playing)
		{
			pos = holdWall >= 0 ? holdWall : Math.min(span, Math.max(0, System.currentTimeMillis() - r.playStartMs));
		}
		return new long[]{pos, span};
	}

	/** True in "paused mode": frozen on a frame (pause, hold) or animating one tick step that will freeze by itself. */
	static boolean isPaused(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		return r.playing && (r.holdIndex >= 0 || r.pauseAtIdx >= 0);
	}

	/** Index of the recorded frame currently shown (the paused frame, else the last replayed one). */
	static int shownIndex(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		return r.holdIndex >= 0 ? r.holdIndex : r.lastReplayIndex;
	}

	/** Freezes playback on the frame currently shown (also stops an animating tick step where it is). */
	static boolean pause(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		if (!r.playing)
		{
			return false;
		}
		if (r.holdIndex < 0)
		{
			r.holdIndex = Math.max(0, r.lastReplayIndex);
		}
		r.pauseAtIdx = -1;
		return true;
	}

	/** Continues real-time playback: from the frozen frame, or, mid tick step, just without the freeze at its end. */
	static boolean resume(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		if (!r.playing || (r.holdIndex < 0 && r.pauseAtIdx < 0))
		{
			return false;
		}
		if (r.holdIndex >= 0)
		{
			synchronized (r.lock)
			{
				if (r.retained.isEmpty())
				{
					return false;
				}
				int idx = Math.min(r.holdIndex, r.retained.size() - 1);
				r.playStartMs = System.currentTimeMillis() - (r.retained.get(idx).wallMs - r.retained.get(0).wallMs);
			}
			r.alignPending = true;
			r.holdIndex = -1;
		}
		r.pauseAtIdx = -1;
		return true;
	}

	/** Auto-repeat only extends a running step when the playhead is within this many ms of the step's end. */
	static volatile long repeatExtendWithinMs = 250;
	/** A discrete step press may queue at most this many ticks ahead of the one being shown. */
	static volatile int maxQueuedTicks = 4;

	/** Discrete step (a tap or a click). */
	static int stepTick(DrawCallbacks cb, int dir)
	{
		return stepTick(cb, dir, false);
	}

	/**
	 * Steps by game ticks. Forward (dir > 0) PLAYS the frames up to the first frame of the next tick at recorded speed,
	 * then freezes. A discrete press while a step is animating queues one more tick (up to maxQueuedTicks); a key
	 * auto-repeat (repeat = true) only extends the run once the playhead is within repeatExtendWithinMs of its end, so
	 * holding the key plays on continuously and stops within a tick of letting go, never running ahead.
	 * Backward (dir < 0) freezes and jumps to the START of the current tick, or of the previous tick if already at a start.
	 * Returns the frame the step is heading to / now shows, or -1 if not in paused mode.
	 */
	static int stepTick(DrawCallbacks cb, int dir, boolean repeat)
	{
		DrawRecorder r = handler(cb);
		if (!r.playing || (r.holdIndex < 0 && r.pauseAtIdx < 0))
		{
			return -1;
		}
		synchronized (r.lock)
		{
			int n = r.retained.size();
			if (n == 0)
			{
				return -1;
			}
			int shown = Math.max(0, Math.min(r.holdIndex >= 0 ? r.holdIndex : r.lastReplayIndex, n - 1));
			long base = r.retained.get(0).wallMs;
			if (dir > 0)
			{
				boolean running = r.pauseAtIdx >= 0;
				int from = running ? Math.min(r.pauseAtIdx, n - 1) : shown;   // a running step is extended from its end
				if (running)
				{
					long ahead = r.retained.get(from).wallMs - r.retained.get(shown).wallMs;
					if (repeat && ahead > repeatExtendWithinMs)
					{
						return from;   // key auto-repeat while the current tick is still playing: ignore it
					}
					if (r.retained.get(from).tick - r.retained.get(shown).tick >= maxQueuedTicks)
					{
						return from;   // queue is full
					}
				}
				int tick = r.retained.get(from).tick, target = from, next = Integer.MAX_VALUE;
				for (int i = from; i < n; i++)
				{
					int t = r.retained.get(i).tick;
					if (t > tick && t < next)
					{
						next = t;
						target = i;
					}
					if (t > tick && t == next)
					{
						break; // first frame of the nearest later tick
					}
				}
				if (target == from)
				{
					return from; // already at the last tick
				}
				if (r.holdIndex >= 0)
				{
					// frozen: start real-time from the frozen frame
					r.playStartMs = System.currentTimeMillis() - (r.retained.get(shown).wallMs - base);
					r.holdIndex = -1;
				}
				r.pauseAtIdx = target;
				return target;
			}
			// backward: stop any animating step first
			r.holdIndex = shown;
			r.pauseAtIdx = -1;
			int tick = r.retained.get(shown).tick;
			int start = shown;
			while (start > 0 && r.retained.get(start - 1).tick == tick)
			{
				start--;                       // first frame of the tick being shown
			}
			int target = start;
			if (start == shown)
			{
				// already at the start of this tick: go to the start of the previous tick
				int prev = Integer.MIN_VALUE, at = shown;
				for (int i = shown; i >= 0; i--)
				{
					int t = r.retained.get(i).tick;
					if (t < tick && t > prev)
					{
						prev = t;
						at = i;
					}
				}
				if (prev != Integer.MIN_VALUE)
				{
					while (at > 0 && r.retained.get(at - 1).tick == prev)
					{
						at--;
					}
					target = at;
				}
			}
			r.holdIndex = target;
			return target;
		}
	}

	/** Game tick of the frame being shown (paused frame, else the last replayed one), or -1. */
	static int replayTick(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		synchronized (r.lock)
		{
			int i = r.holdIndex >= 0 ? r.holdIndex : r.lastReplayIndex;
			return i >= 0 && i < r.retained.size() ? r.retained.get(i).tick : -1;
		}
	}

	/**
	 * Writes the retained frames as a binary table: int magic, int count, then per frame long wallMs, int tick, int scale,
	 * byte keyframe, 16 unused bytes, float playerX, float playerZ, int length, the 2D-layer delta. Returns the frame count.
	 */
	static int exportFrames(DrawCallbacks cb, java.io.OutputStream os) throws java.io.IOException
	{
		DrawRecorder r = handler(cb);
		java.io.DataOutputStream d = new java.io.DataOutputStream(os);
		List<RawFrame> frames;
		synchronized (r.lock)
		{
			frames = new ArrayList<>(r.retained);
		}
		d.writeInt(RecordingFile.FRAMES_MAGIC);
		d.writeInt(frames.size());
		for (RawFrame f : frames)
		{
			d.writeLong(f.wallMs);
			d.writeInt(f.tick);
			d.writeInt(f.scale);
			d.writeByte(f.keyframe ? 1 : 0);
			d.writeLong(0); // unused
			d.writeLong(0);
			d.writeFloat(f.playerX);
			d.writeFloat(f.playerZ);
			byte[] u = f.uiDelta == null ? new byte[0] : f.uiDelta;
			d.writeInt(u.length);
			d.write(u);
		}
		d.flush();
		return frames.size();
	}

	/** Jumps a running playback to offsetMs (real-time mode) or to the frame at that time (hold mode). */
	static void seek(DrawCallbacks cb, long offsetMs)
	{
		DrawRecorder r = handler(cb);
		if (r.holdIndex >= 0)
		{
			synchronized (r.lock)
			{
				if (r.retained.isEmpty())
				{
					return;
				}
				long base = r.retained.get(0).wallMs;
				int idx = 0;
				for (int i = 0; i < r.retained.size(); i++)
				{
					if (r.retained.get(i).wallMs - base > offsetMs)
					{
						break;
					}
					idx = i;
				}
				r.holdIndex = idx;
			}
		}
		else
		{
			r.playStartMs = System.currentTimeMillis() - Math.max(0, offsetMs);
			r.alignPending = true;
		}
	}

	/** Ends capture: from now on this wrapper only forwards and, when asked, replays. */
	static void stopCapture(DrawCallbacks cb)
	{
		handler(cb).capturing = false;
	}

	/** Estimated bytes held by retained frames. */
	static long retainedBytes(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		synchronized (r.lock)
		{
			return r.retainedBytes;
		}
	}

	static int retainedFrames(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		synchronized (r.lock)
		{
			return r.retained.size();
		}
	}

	static boolean hasRetained(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		synchronized (r.lock)
		{
			return !r.retained.isEmpty();
		}
	}

	static void startPlayback(DrawCallbacks cb, Runnable onEnd)
	{
		startPlayback(cb, onEnd, -1, 0);
	}

	/** Starts offsetMs into the recording. holdIndex >= 0 starts frozen on that frame. */
	static void startPlayback(DrawCallbacks cb, Runnable onEnd, int holdIndex, long offsetMs)
	{
		DrawRecorder r = handler(cb);
		r.holdIndex = holdIndex;
		r.pauseAtIdx = -1;
		r.uiBuf = null;
		r.uiAppliedIdx = -1;
		r.onPlaybackEnd = onEnd;
		r.playStartMs = System.currentTimeMillis() - Math.max(0, offsetMs);
		r.alignPending = holdIndex < 0;
		r.lastReplayIndex = -1;
		r.clientScene = r.probe == null ? null : r.probe.liveScene();
		r.sceneInUse = r.clientScene;
		r.liveLoadPending = false;
		r.endRequested = false;
		r.playing = true;
	}

	/** Zoom scale recorded for the frame being replayed, or 0. */
	static int replayScale(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		List<RawFrame> f = r.playFrames;
		int i = r.lastReplayIndex;
		return i >= 0 && i < f.size() ? f.get(i).scale : 0;
	}

	static void stopPlayback(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		r.restoreLiveScene();
		r.playing = false;
	}

	// FloatProjection -> its frozen copy for the frame being recorded; guarded by lock, cleared at every frame end.
	private final Map<Object, Object> projCache = new IdentityHashMap<>();

	/** Copy of the call's args with everything the client rewrites after the frame replaced by a frozen copy. */

	private Object[] frozen(Call m, Object[] args, Object[] row)
	{
		if (args == null)
		{
			return null;
		}
		Object[] out = args.clone();
		for (int i = 0; i < out.length; i++)
		{
			if (out[i] instanceof Model && row[i + 1] instanceof Map)
			{
				Object id = ((Map<?, ?>) row[i + 1]).get("model");
				Model snap;
				synchronized (snapshots)
				{
					snap = id instanceof Integer ? snapshots.get(id) : null;
				}
				if (snap != null)
				{
					out[i] = snap;
				}
			}
		}
		for (int i = 0; i < out.length; i++)
		{
			if (out[i] instanceof Set)
			{
				// preSceneDraw's roof-removal set is one object the client keeps mutating
				out[i] = new HashSet<Object>((Set<?>) out[i]);
			}
			else if (out[i] instanceof net.runelite.api.FloatProjection && !(out[i] instanceof FrozenProjection))
			{
				// A boat's transform: the client changes and recycles the object after the frame.
				out[i] = projCache.computeIfAbsent(out[i], k -> FrozenProjection.of((net.runelite.api.FloatProjection) k));
			}
		}
		if (m.methodName.equals("drawTemp"))
		{
			for (int i = 0; i < out.length; i++)
			{
				if (out[i] instanceof net.runelite.api.GameObject)
				{
					out[i] = snapshotGameObject((net.runelite.api.GameObject) out[i]);
				}
			}
		}
		return out;
	}

	private byte[] captureUi(boolean key)
	{
		if (probe == null)
		{
			return null;
		}
		int[] px = probe.uiPixels();
		if (px == null)
		{
			return null;
		}
		byte[] enc = UiFrames.encode(key ? null : prevUi, px);
		if (prevUi == null || prevUi.length != px.length)
		{
			prevUi = new int[px.length];
		}
		System.arraycopy(px, 0, prevUi, 0, px.length);
		return enc;
	}

	/** Overwrites the live 2D layer with the recorded one for the frame currently being replayed. */
	private void applyUi()
	{
		int idx = lastReplayIndex;
		List<RawFrame> frames = playFrames;
		if (probe == null || idx < 0 || idx >= frames.size())
		{
			return;
		}
		if (idx < uiAppliedIdx)
		{
			uiAppliedIdx = -1;
			uiBuf = null;
		}
			// Decode forward from the last applied frame, or from the nearest keyframe when starting cold.
		int from = uiAppliedIdx + 1;
		if (uiAppliedIdx < 0)
		{
			from = idx;
			while (from > 0 && !frames.get(from).keyframe)
			{
				from--;
			}
		}
		for (int i = from; i <= idx; i++)
		{
			byte[] e = frames.get(i).uiDelta;
			if (e == null)
			{
				continue;
			}
			int len = UiFrames.length(e);
			if (uiBuf == null || uiBuf.length != len)
			{
				uiBuf = new int[len];
			}
			UiFrames.applyFrame(e, uiBuf, frames.get(i).keyframe);
		}
		uiAppliedIdx = idx;
		int[] live = probe.uiPixels();
		uiSizeMismatch = live != null && uiBuf != null && live.length != uiBuf.length;
		if (live != null && uiBuf != null && live.length == uiBuf.length)
		{
			System.arraycopy(uiBuf, 0, live, 0, live.length);
		}
	}

	private volatile boolean uiSizeMismatch;

	/** True while playing when the recorded 2D layer has a different pixel count than the live canvas (window resized). */
	static boolean uiSizeMismatch(DrawCallbacks cb)
	{
		DrawRecorder r = handler(cb);
		return r.playing && r.uiSizeMismatch;
	}

	/**
	 * The renderer projects with the live zoom, while the recorded 2D layer was drawn at the recorded zoom. Screen size
	 * goes as scale / depth, so the camera's offset from its focus (the point above the player it orbits) is scaled by
	 * liveScale / recordedScale. Exact at the focus depth, approximate away from it. Only the args handed to the
	 * renderer change.
	 */
	private static Object[] compensateZoom(Call m, Object[] args, RawFrame f, int liveScale)
	{
		if (args == null || liveScale <= 0 || f.scale <= 0 || liveScale == f.scale || Float.isNaN(f.playerX))
		{
			return args;
		}
		int ix;
		switch (m.methodName)
		{
			case "drawScene":
				ix = 0;
				break;
			case "preSceneDraw":
				ix = args.length > 1 && args[1] instanceof Number ? 1 : 2;
				break;
			default:
				return args;
		}
		if (args.length < ix + 4 || !(args[ix] instanceof Number))
		{
			return args;
		}
		double cx = ((Number) args[ix]).doubleValue(), cy = ((Number) args[ix + 1]).doubleValue();
		double cz = ((Number) args[ix + 2]).doubleValue(), pitch = ((Number) args[ix + 3]).doubleValue();
		double h = Math.hypot(cx - f.playerX, cz - f.playerZ);
		double fy = cy + h * Math.tan(pitch);
		double k = (double) liveScale / f.scale;
		Object[] out = args.clone();
		out[ix] = like(args[ix], f.playerX + k * (cx - f.playerX));
		out[ix + 1] = like(args[ix + 1], fy + k * (cy - fy));
		out[ix + 2] = like(args[ix + 2], f.playerZ + k * (cz - f.playerZ));
		return out;
	}

	private static Object like(Object original, double v)
	{
		return original instanceof Float ? (Object) (float) v : (Object) v;
	}

	/** drawDynamic's worker index is dealt round-robin, as the client's dispatcher does; -1 (not a worker) stays. */
	private Object[] dynIndex(Call m, Object[] args)
	{
		if (m != Call.DRAW_DYNAMIC_WORKER || args == null || !(args[0] instanceof Integer) || (Integer) args[0] < 0)
		{
			return args;
		}
		Object[] out = args.clone();
		out[0] = (int) (dynCounter++ % Math.max(1, dynWorkers));
		return out;
	}

	/**
	 * drawTemp, which draws actors, is handed a temporary GameObject wrapper that the client reuses for every actor. By
	 * replay time it no longer holds the actor, so what the GPU plugin reads from it is captured at record time.
	 */
	static net.runelite.api.GameObject snapshotGameObject(net.runelite.api.GameObject go)
	{
		StandIns.StandInGameObject g = new StandIns.StandInGameObject();
		try
		{
			g.renderable = snapshotRenderable(go.getRenderable());
			g.hash = go.getHash();
			g.id = go.getId();
			g.x = go.getX();
			g.y = go.getY();
			g.z = go.getZ();
			g.plane = go.getPlane();
			g.orientation = go.getOrientation();
			g.modelOrientation = go.getModelOrientation();
			g.config = go.getConfig();
			g.sizeX = go.sizeX();
			g.sizeY = go.sizeY();
			g.min = go.getSceneMinLocation();
			g.max = go.getSceneMaxLocation();
			g.wv = go.getWorldView();
		}
		catch (RuntimeException e)
		{
			return go; // cannot snapshot: fall back to the live object
		}
		return g;
	}

	/** drawTemp also reads the actor's model height and render mode, which can fail once the actor has despawned. */
	static net.runelite.api.Renderable snapshotRenderable(net.runelite.api.Renderable r)
	{
		if (r == null)
		{
			return null;
		}
		StandIns.StandInRenderable s = new StandIns.StandInRenderable();
		try
		{
			s.height = r.getModelHeight();
			s.mode = r.getRenderMode();
		}
		catch (RuntimeException e)
		{
			return r;
		}
		return s;
	}
}
