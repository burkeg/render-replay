package com.renderreplay;

import net.runelite.api.Projection;

/**
 * The client's world projection, rebuilt from a frame's drawScene camera (x, y, z, pitch, yaw): moves a point into
 * camera space. The GPU plugin calls it to sort a model's faces and to drop those facing away, so a replayed frame
 * must hand it the recorded camera, not the live one.
 */
final class CameraProjection implements Projection
{
	private final double cx, cy, cz, ps, pc, ys, yc;

	CameraProjection(double cx, double cy, double cz, double pitch, double yaw)
	{
		this.cx = cx;
		this.cy = cy;
		this.cz = cz;
		this.ps = Math.sin(pitch);
		this.pc = Math.cos(pitch);
		this.ys = Math.sin(yaw);
		this.yc = Math.cos(yaw);
	}

	/** The projection of a drawScene call's arguments, or null if they are not a camera. */
	static CameraProjection of(Object[] drawSceneArgs)
	{
		if (drawSceneArgs == null || drawSceneArgs.length < 5)
		{
			return null;
		}
		double[] v = new double[5];
		for (int i = 0; i < 5; i++)
		{
			if (!(drawSceneArgs[i] instanceof Number))
			{
				return null;
			}
			v[i] = ((Number) drawSceneArgs[i]).doubleValue();
		}
		return new CameraProjection(v[0], v[1], v[2], v[3], v[4]);
	}

	@Override
	public float[] project(float x, float y, float z)
	{
		return project(x, y, z, new float[3]);
	}

	@Override
	public float[] project(float x, float y, float z, float[] out)
	{
		if (out == null || out.length < 3)
		{
			out = new float[3];
		}
		double dx = x - cx, dy = y - cy, dz = z - cz;
		double x1 = dx * yc + dz * ys;
		double z1 = dz * yc - dx * ys;
		out[0] = (float) x1;
		out[1] = (float) (dy * pc - z1 * ps);
		out[2] = (float) (dy * ps + z1 * pc);
		return out;
	}
}
