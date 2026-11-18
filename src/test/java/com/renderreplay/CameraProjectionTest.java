package com.renderreplay;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class CameraProjectionTest
{
	private static final float[][] POINTS = {{0, 0, 0}, {128, 0, 0}, {0, 128, 0}, {0, 0, 128}};

	private static void check(Object[] scene, float[][] recorded)
	{
		CameraProjection p = CameraProjection.of(scene);
		for (int i = 0; i < POINTS.length; i++)
		{
			assertArrayEquals("point " + i, recorded[i], p.project(POINTS[i][0], POINTS[i][1], POINTS[i][2]), 0.01f);
		}
	}

	// What the client's own projection gave for these points, recorded in game with the frame's drawScene camera.
	@Test
	public void matchesTheClientsProjectionLookingAhead()
	{
		check(new Object[]{6464.0, -802.7006225585938, 5455.6279296875, 0.39269909262657166, 0.0, 0},
			new float[][]{{-6464.0f, 2829.3772f, -4733.1626f}, {-6336.0f, 2829.3772f, -4733.1626f}, {-6464.0f, 2947.6338f, -4684.179f}, {-6464.0f, 2780.3936f, -4614.9062f}});
	}

	@Test
	public void matchesTheClientsProjectionTurnedAndTilted()
	{
		check(new Object[]{5716.73779296875, -1217.7740478515625, 5616.17822265625, 0.6244726777076721, -0.6535221338272095, 0},
			new float[][]{{-1124.2275f, 5627.0933f, -5725.16f}, {-1022.60254f, 5581.593f, -5662.0254f}, {-1124.2275f, 5730.936f, -5650.3223f}, {-1202.0498f, 5567.676f, -5642.715f}});
	}
}
