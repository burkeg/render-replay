package com.renderreplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls between two kept frames, for presets that keep only every n-th frame: the camera and every object's position
 * and facing move smoothly from one kept frame to the next; poses and everything else stay as in the earlier one.
 */
final class Interp
{
	private Interp()
	{
	}

	private static double lerp(double a, double b, double t)
	{
		return a + (b - a) * t;
	}

	private static double lerpRad(double a, double b, double t)
	{
		double d = (b - a) % (2 * Math.PI);
		if (d > Math.PI)
		{
			d -= 2 * Math.PI;
		}
		if (d < -Math.PI)
		{
			d += 2 * Math.PI;
		}
		return a + d * t;
	}

	private static int lerpJau(int a, int b, double t)
	{
		int d = Math.floorMod(b - a + 1024, 2048) - 1024;
		return (int) Math.round(a + d * t) & 2047;
	}

	/** Calls a (with identities ida) moved a fraction t of the way to calls b (with identities idb). */
	static List<Object[]> between(List<Object[]> a, String[] ida, List<Object[]> b, String[] idb, double t)
	{
		Map<String, Object[]> byId = new HashMap<>();
		Object[] sceneB = null, preB = null;
		for (int j = 0; j < b.size(); j++)
		{
			Object[] c = b.get(j);
			Call m = (Call) c[0];
			if (m == Call.DRAW_SCENE)
			{
				sceneB = c;
			}
			else if (m == Call.PRE_SCENE_DRAW || m == Call.PRE_SCENE_DRAW_PROJ)
			{
				preB = c;
			}
			else if (idb[j] != null && idb[j].indexOf('#') > 0)
			{
				byId.put(idb[j], c);
			}
		}
		List<Object[]> out = new ArrayList<>(a.size());
		for (int j = 0; j < a.size(); j++)
		{
			Object[] c = a.get(j);
			Call m = (Call) c[0];
			Object[] args = (Object[]) c[1];
			try
			{
				if (m == Call.DRAW_SCENE && sceneB != null)
				{
					Object[] q = (Object[]) sceneB[1], o = args.clone();
					for (int i = 0; i < 4; i++)
					{
						o[i] = lerp((Double) args[i], (Double) q[i], t);
					}
					o[4] = lerpRad((Double) args[4], (Double) q[4], t);
					out.add(new Object[]{m, o});
					continue;
				}
				if ((m == Call.PRE_SCENE_DRAW || m == Call.PRE_SCENE_DRAW_PROJ) && preB != null && preB[0] == m)
				{
					Object[] q = (Object[]) preB[1], o = args.clone();
					int f0 = m == Call.PRE_SCENE_DRAW_PROJ ? 2 : 1;
					for (int i = f0; i < f0 + 4; i++)
					{
						o[i] = (float) lerp((Float) args[i], (Float) q[i], t);
					}
					o[f0 + 4] = (float) lerpRad((Float) args[f0 + 4], (Float) q[f0 + 4], t);
					out.add(new Object[]{m, o});
					continue;
				}
				if ((m == Call.DRAW_DYNAMIC || m == Call.DRAW_DYNAMIC_WORKER || m == Call.DRAW_TEMP) && ida[j] != null)
				{
					Object[] qc = byId.get(ida[j]);
					if (qc != null && qc[0] == m)
					{
						Object[] q = (Object[]) qc[1], o = args.clone();
						int l4 = args.length - 4;
						o[l4] = lerpJau((Integer) args[l4], (Integer) q[l4], t);
						for (int i = l4 + 1; i < args.length; i++)
						{
							o[i] = (int) Math.round(lerp((Integer) args[i], (Integer) q[i], t));
						}
						out.add(new Object[]{m, o});
						continue;
					}
				}
			}
			catch (RuntimeException e)
			{
				// an argument of another type than expected: leave the call as it was
			}
			out.add(c);
		}
		return out;
	}
}
