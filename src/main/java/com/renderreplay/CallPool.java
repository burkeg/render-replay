package com.renderreplay;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shares equal draw calls between consecutive frames. Nearly every call of a frame repeats one of the frame before, so
 * a frame keeps a reference to that call instead of its own copy. A call is {Call, Object[] args}; shared calls must
 * not be written to.
 */
final class CallPool
{
	private Map<Key, Object[]> previous = new HashMap<>();
	private Map<Key, Object[]> current = new HashMap<>();
	private long added;

	/** The equal call of this frame or the previous one, or a new call. */
	Object[] share(Call m, Object[] args)
	{
		Key k = new Key(m, args);
		Object[] call = current.get(k);
		if (call == null)
		{
			call = previous.get(k);
			if (call == null)
			{
				call = new Object[]{m, args};
				added += sizeOf(args);
			}
			k.args = (Object[]) call[1];
			current.put(k, call);
		}
		return call;
	}

	/** Estimated bytes of a call that is not shared: its two arrays, boxed numbers and the copies frozen for it. */
	private static long sizeOf(Object[] args)
	{
		long bytes = 48;
		for (int i = 0; args != null && i < args.length; i++)
		{
			Object a = args[i];
			bytes += 4;
			if (a instanceof Double || a instanceof Long)
			{
				bytes += 24;
			}
			else if (a instanceof Float || (a instanceof Integer && ((Integer) a < -128 || (Integer) a > 127)))
			{
				bytes += 16;
			}
			else if (a instanceof Set)
			{
				bytes += 64 + 48L * ((Set<?>) a).size();
			}
			else if (a instanceof FrozenProjection)
			{
				bytes += 96;
			}
			else if (a instanceof StandIns.StandInGameObject)
			{
				bytes += 160;
			}
		}
		return bytes;
	}

	/** Ends the frame. Returns the estimated bytes of the calls it could not share. */
	long endFrame()
	{
		previous = current;
		current = new HashMap<>();
		long bytes = added;
		added = 0;
		return bytes;
	}

	private static final class Key
	{
		private final Call m;
		private Object[] args;
		private final int hash;

		Key(Call m, Object[] args)
		{
			this.m = m;
			this.args = args;
			int h = m.hashCode();
			for (int i = 0; args != null && i < args.length; i++)
			{
				h = 31 * h + hashOf(args[i]);
			}
			this.hash = h;
		}

		@Override
		public int hashCode()
		{
			return hash;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Key))
			{
				return false;
			}
			Key k = (Key) o;
			if (m != k.m || hash != k.hash || (args == null) != (k.args == null))
			{
				return false;
			}
			if (args == null || args == k.args)
			{
				return true;
			}
			if (args.length != k.args.length)
			{
				return false;
			}
			for (int i = 0; i < args.length; i++)
			{
				if (!same(args[i], k.args[i]))
				{
					return false;
				}
			}
			return true;
		}
	}

	// Values and the recorder's own frozen copies are compared by content; everything else (scenes, models, live
	// objects) is the same only when it is the same object.

	private static boolean byContent(Object a)
	{
		return a instanceof Number || a instanceof Boolean || a instanceof Set || a instanceof FrozenProjection;
	}

	private static int hashOf(Object a)
	{
		if (a == null)
		{
			return 0;
		}
		if (byContent(a))
		{
			return a.hashCode();
		}
		if (a instanceof StandIns.StandInGameObject)
		{
			StandIns.StandInGameObject g = (StandIns.StandInGameObject) a;
			return 31 * (31 * Long.hashCode(g.hash) + g.x) + g.y;
		}
		return System.identityHashCode(a);
	}

	private static boolean same(Object a, Object b)
	{
		if (a == b)
		{
			return true;
		}
		if (a == null || b == null)
		{
			return false;
		}
		if (byContent(a))
		{
			return a.equals(b);
		}
		if (a instanceof StandIns.StandInGameObject && b instanceof StandIns.StandInGameObject)
		{
			StandIns.StandInGameObject g = (StandIns.StandInGameObject) a, h = (StandIns.StandInGameObject) b;
			return g.hash == h.hash && g.id == h.id && g.plane == h.plane && g.x == h.x && g.y == h.y && g.z == h.z
				&& g.orientation == h.orientation && g.modelOrientation == h.modelOrientation && g.config == h.config
				&& g.sizeX == h.sizeX && g.sizeY == h.sizeY && g.wv == h.wv
				&& Objects.equals(g.min, h.min) && Objects.equals(g.max, h.max) && same(g.renderable, h.renderable);
		}
		if (a instanceof StandIns.StandInRenderable && b instanceof StandIns.StandInRenderable)
		{
			StandIns.StandInRenderable r = (StandIns.StandInRenderable) a, s = (StandIns.StandInRenderable) b;
			return r.height == s.height && r.mode == s.mode && r.model == s.model;
		}
		return false;
	}
}
