package com.renderreplay;

import net.runelite.api.FloatProjection;

/**
 * A FloatProjection (the transform of a boat or other sub world view) copied when a frame is recorded. The client hands the
 * renderer a projection object per world entity and changes or recycles it afterwards, so replaying the live object draws the
 * boat with whatever it holds by then. GpuPlugin reads getProjection() (preSceneDraw uploads it as the entity matrix) and may call
 * project(). The matrix is column-major and affine: project(v) = M * (v, 1).
 */
final class FrozenProjection implements FloatProjection
{
	private final float[] m;

	private FrozenProjection(float[] m)
	{
		this.m = m;
	}

	/**
	 * A frozen copy of p, or p itself when its project() is not the affine map of its matrix (then copying would change what the
	 * renderer computes). Checked on four points, the same ones DrawRecorder.describe records.
	 */
	static FloatProjection of(FloatProjection p)
	{
		try
		{
			float[] src = p.getProjection();
			if (src == null || src.length != 16)
			{
				return p;
			}
			FrozenProjection f = new FrozenProjection(src.clone());
			float[][] pts = {{0, 0, 0}, {128, 0, 0}, {0, 128, 0}, {0, 0, 128}};
			for (float[] v : pts)
			{
				float[] a = p.project(v[0], v[1], v[2]);
				float[] b = f.project(v[0], v[1], v[2]);
				for (int i = 0; i < 3; i++)
				{
					if (a == null || a.length < 3 || Math.abs(a[i] - b[i]) > 1e-3f * Math.max(1f, Math.abs(a[i])))
					{
						return p;
					}
				}
			}
			return f;
		}
		catch (RuntimeException e)
		{
			return p;
		}
	}

	@Override
	public float[] getProjection()
	{
		return m.clone();
	}

	@Override
	public float[] project(float x, float y, float z)
	{
		return project(x, y, z, new float[3]);
	}

	@Override
	public float[] project(float x, float y, float z, float[] out)
	{
		float[] o = out != null && out.length >= 3 ? out : new float[3];
		float px = m[0] * x + m[4] * y + m[8] * z + m[12];
		float py = m[1] * x + m[5] * y + m[9] * z + m[13];
		float pz = m[2] * x + m[6] * y + m[10] * z + m[14];
		o[0] = px;
		o[1] = py;
		o[2] = pz;
		return o;
	}
}
