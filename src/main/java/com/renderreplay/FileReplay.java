package com.renderreplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.TileObject;

/**
 * Stand-in objects rebuilt from a recording file, so a saved recording can be replayed without the live objects it was
 * captured from. The GPU plugin only reads these through the API interfaces, so an object that answers the same getters
 * from the saved values is enough; anything not saved answers a neutral default.
 */
final class FileReplay
{
	private FileReplay()
	{
	}

	static Object convert(Object v, Class<?> t)
	{
		if (v instanceof Number)
		{
			Number n = (Number) v;
			if (t == int.class || t == Integer.class) return n.intValue();
			if (t == long.class || t == Long.class) return n.longValue();
			if (t == float.class || t == Float.class) return n.floatValue();
			if (t == double.class || t == Double.class) return n.doubleValue();
			if (t == short.class || t == Short.class) return n.shortValue();
			if (t == byte.class || t == Byte.class) return n.byteValue();
		}
		return v;
	}

	// ---- JSON helpers (null-safe, typed arrays) ----

	static int[] ints(JsonObject o, String k)
	{
		if (!o.has(k) || o.get(k).isJsonNull())
		{
			return null;
		}
		JsonArray a = o.getAsJsonArray(k);
		int[] r = new int[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
		return r;
	}

	static float[] floats(JsonObject o, String k)
	{
		if (!o.has(k) || o.get(k).isJsonNull())
		{
			return null;
		}
		JsonArray a = o.getAsJsonArray(k);
		float[] r = new float[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
		return r;
	}

	static byte[] bytes(JsonObject o, String k)
	{
		if (!o.has(k) || o.get(k).isJsonNull())
		{
			return null;
		}
		JsonArray a = o.getAsJsonArray(k);
		byte[] r = new byte[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = (byte) a.get(i).getAsInt();
		return r;
	}

	static short[] shorts(JsonObject o, String k)
	{
		if (!o.has(k) || o.get(k).isJsonNull())
		{
			return null;
		}
		JsonArray a = o.getAsJsonArray(k);
		short[] r = new short[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = (short) a.get(i).getAsInt();
		return r;
	}

	private static int intOr(JsonObject o, String k, int d)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d;
	}

	// ---- stand-ins ----

	/** From a models.jsonl "model" entry. */
	static Model model(JsonObject b)
	{
		StandIns.StandInModel m = new StandIns.StandInModel();
		m.vn = intOr(b, "vn", 0);
		m.fn = intOr(b, "fn", 0);
		m.vx = floats(b, "vx");
		m.vy = floats(b, "vy");
		m.vz = floats(b, "vz");
		m.f1 = ints(b, "f1");
		m.f2 = ints(b, "f2");
		m.f3 = ints(b, "f3");
		m.c1 = ints(b, "c1");
		m.c2 = ints(b, "c2");
		m.c3 = ints(b, "c3");
		m.tr = bytes(b, "tr");
		m.pri = bytes(b, "pri");
		m.ft = shorts(b, "ft");
		m.fb = bytes(b, "fb");
		m.tf = bytes(b, "tf");
		m.t1 = ints(b, "t1");
		m.t2 = ints(b, "t2");
		m.t3 = ints(b, "t3");
		int[] ov = ints(b, "ov");
		if (ov != null && ov.length == 4)
		{
			m.ov0 = (byte) ov[0];
			m.ov1 = (byte) ov[1];
			m.ov2 = (byte) ov[2];
			m.ov3 = (byte) ov[3];
		}
		m.tp = (byte) intOr(b, "tp", 0);
		boundsCylinder(m);
		return m;
	}

	/**
	 * A frozen copy of a live model, holding the same fields a models.jsonl "model" entry carries. The client reuses and
	 * rewrites its Model objects, so playback draws these copies instead. Arrays are copied, never shared.
	 */
	static Model copyOf(Model src)
	{
		StandIns.StandInModel m = new StandIns.StandInModel();
		m.vn = src.getVerticesCount();
		m.fn = src.getFaceCount();
		m.vx = copy(src.getVerticesX(), m.vn);
		m.vy = copy(src.getVerticesY(), m.vn);
		m.vz = copy(src.getVerticesZ(), m.vn);
		m.f1 = copy(src.getFaceIndices1(), m.fn);
		m.f2 = copy(src.getFaceIndices2(), m.fn);
		m.f3 = copy(src.getFaceIndices3(), m.fn);
		m.c1 = copy(src.getFaceColors1(), m.fn);
		m.c2 = copy(src.getFaceColors2(), m.fn);
		m.c3 = copy(src.getFaceColors3(), m.fn);
		m.tr = copy(src.getFaceTransparencies(), m.fn);
		m.pri = copy(src.getFaceRenderPriorities(), m.fn);
		m.ft = copy(src.getFaceTextures(), m.fn);
		m.fb = copy(src.getFaceBias(), m.fn);
		// the texture arrays are indexed by texture face, not by face: keep their full length
		byte[] tf = src.getTextureFaces();
		m.tf = tf == null ? null : tf.clone();
		int[] t1 = src.getTexIndices1(), t2 = src.getTexIndices2(), t3 = src.getTexIndices3();
		m.t1 = t1 == null ? null : t1.clone();
		m.t2 = t2 == null ? null : t2.clone();
		m.t3 = t3 == null ? null : t3.clone();
		m.ov0 = src.getOverrideAmount();
		m.ov1 = src.getOverrideHue();
		m.ov2 = src.getOverrideSaturation();
		m.ov3 = src.getOverrideLuminance();
		m.tp = src.getTransparency();
		boundsCylinder(m);
		return m;
	}

	/**
	 * Radius and diameter as the client's calculateBoundsCylinder() computes them. The client fills them in lazily, and
	 * the GPU plugin asks for that just before it sorts a model's faces by depth; a stand-in cannot compute them later.
	 */
	private static void boundsCylinder(StandIns.StandInModel m)
	{
		float top = 0, bottom = 0, xz2 = 0;
		for (int i = 0; m.vx != null && i < m.vn && i < m.vx.length; i++)
		{
			float x = m.vx[i], y = m.vy[i], z = m.vz[i];
			top = Math.max(top, -y);
			bottom = Math.max(bottom, y);
			xz2 = Math.max(xz2, x * x + z * z);
		}
		int xz = (int) (Math.sqrt(xz2) + 0.99);
		m.rad = (int) (Math.sqrt(xz * xz + top * top) + 0.99);
		m.dia = m.rad + (int) (Math.sqrt(xz * xz + bottom * bottom) + 0.99);
	}

	// The API hands back oversized backing arrays; only the first n entries are real.

	private static int[] copy(int[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	private static float[] copy(float[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	private static short[] copy(short[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	private static byte[] copy(byte[] a, int n)
	{
		return a == null ? null : Arrays.copyOf(a, Math.min(n, a.length));
	}

	/** From a models.jsonl "tilemodel" entry. */
	static SceneTileModel tileModel(JsonObject b)
	{
		StandIns.StandInTileModel t = new StandIns.StandInTileModel();
		t.vx = ints(b, "vx");
		t.vy = ints(b, "vy");
		t.vz = ints(b, "vz");
		t.f1 = ints(b, "f1");
		t.f2 = ints(b, "f2");
		t.f3 = ints(b, "f3");
		t.c1 = ints(b, "c1");
		t.c2 = ints(b, "c2");
		t.c3 = ints(b, "c3");
		t.tex = ints(b, "tex");
		t.flat = b.has("flat") && b.get("flat").getAsBoolean();
		return t;
	}

	/** From a drawScenePaint row cell: [rbg, sw, se, nw, ne, texture, flat]. */
	static SceneTilePaint paint(JsonArray c)
	{
		StandIns.StandInPaint p = new StandIns.StandInPaint();
		p.rbg = c.get(0).getAsInt();
		p.sw = c.get(1).getAsInt();
		p.se = c.get(2).getAsInt();
		p.nw = c.get(3).getAsInt();
		p.ne = c.get(4).getAsInt();
		p.texture = c.get(5).getAsInt();
		p.flat = c.get(6).getAsInt() != 0;
		return p;
	}

	/** From a {"proj":..,"s":[[o],[x128],[y128],[z128]]} cell: an affine map rebuilt from four sample points. */
	static Projection projection(JsonObject o)
	{
		JsonArray s = o.getAsJsonArray("s");
		final float[][] p = new float[4][3];
		for (int i = 0; i < 4; i++)
		{
			for (int j = 0; j < 3; j++)
			{
				p[i][j] = s.get(i).getAsJsonArray().get(j).getAsFloat();
			}
		}
		return new Projection()
		{
			@Override
			public float[] project(float x, float y, float z)
			{
				return project(x, y, z, new float[3]);
			}

			@Override
			public float[] project(float x, float y, float z, float[] out)
			{
				for (int j = 0; j < 3; j++)
				{
					out[j] = p[0][j] + (p[1][j] - p[0][j]) * x / 128f + (p[2][j] - p[0][j]) * y / 128f + (p[3][j] - p[0][j]) * z / 128f;
				}
				return out;
			}
		};
	}

	/** From a {"rend":..,"h":..,"m":..} cell. */
	static Renderable renderable(JsonObject o)
	{
		StandIns.StandInRenderable r = new StandIns.StandInRenderable();
		r.height = intOr(o, "h", 0);
		r.mode = intOr(o, "m", 0);
		return r;
	}

	/** From a {"tobj":id,"plane":..,"hash":..} cell. */
	static TileObject tileObject(JsonObject o)
	{
		StandIns.StandInTileObject t = new StandIns.StandInTileObject();
		t.id = intOr(o, "tobj", 0);
		t.plane = intOr(o, "plane", 0);
		t.hash = o.has("hash") ? o.get("hash").getAsLong() : 0L;
		return t;
	}

	/** From a {"go":id,"hash":..,"xyz":[..],...} cell (drawTemp's actor wrapper). */
	static GameObject gameObject(JsonObject o)
	{
		StandIns.StandInGameObject g = new StandIns.StandInGameObject();
		g.id = intOr(o, "go", 0);
		g.hash = o.has("hash") ? o.get("hash").getAsLong() : 0L;
		int[] xyz = ints(o, "xyz");
		if (xyz != null)
		{
			g.x = xyz[0];
			g.y = xyz[1];
			g.z = xyz[2];
		}
		g.plane = intOr(o, "plane", 0);
		int[] ori = ints(o, "ori");
		if (ori != null)
		{
			g.orientation = ori[0];
			g.modelOrientation = ori[1];
			g.config = ori[2];
		}
		int[] size = ints(o, "size");
		if (size != null)
		{
			g.sizeX = size[0];
			g.sizeY = size[1];
		}
		int[] box = ints(o, "box");
		if (box != null)
		{
			g.min = new net.runelite.api.Point(box[0], box[1]);
			g.max = new net.runelite.api.Point(box[2], box[3]);
		}
		if (o.has("h"))
		{
			g.renderable = renderable(o);
		}
		return g;
	}
}
