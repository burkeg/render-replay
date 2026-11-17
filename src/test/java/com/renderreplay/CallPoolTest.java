package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public class CallPoolTest
{
	@Test
	public void equalCallsOfConsecutiveFramesAreOneObject()
	{
		CallPool pool = new CallPool();
		Object scene = new Object();
		Object[] first = pool.share(Call.DRAW_ZONE_OPAQUE, new Object[]{null, scene, 300, 7});
		assertTrue("a new call is counted", pool.endFrame() > 0);

		// boxed again by the next frame's call: equal values, different objects
		Object[] second = pool.share(Call.DRAW_ZONE_OPAQUE, new Object[]{null, scene, Integer.valueOf(300), 7});
		assertSame(first, second);
		assertEquals("a shared call adds nothing", 0, pool.endFrame());
	}

	@Test
	public void differentArgumentsOrMethodsAreNotShared()
	{
		CallPool pool = new CallPool();
		Object scene = new Object();
		Object[] a = pool.share(Call.DRAW_ZONE_OPAQUE, new Object[]{null, scene, 3, 7});
		assertNotSame(a, pool.share(Call.DRAW_ZONE_OPAQUE, new Object[]{null, scene, 3, 8}));
		assertNotSame(a, pool.share(Call.DRAW_ZONE_OPAQUE, new Object[]{null, new Object(), 3, 7}));
		assertNotSame(a, pool.share(Call.DRAW_PASS, new Object[]{null, scene, 3}));
		assertSame(a, pool.share(Call.DRAW_ZONE_OPAQUE, new Object[]{null, scene, 3, 7}));
	}

	@Test
	public void liveObjectsAreComparedByIdentityAndFrozenCopiesByContent()
	{
		CallPool pool = new CallPool();
		StandIns.StandInTileObject live = new StandIns.StandInTileObject(), other = new StandIns.StandInTileObject();
		Object[] a = pool.share(Call.DRAW_DYNAMIC, new Object[]{null, null, live, null, null, 0, 1, 2, 3});
		assertNotSame("two objects, even with equal fields", a,
			pool.share(Call.DRAW_DYNAMIC, new Object[]{null, null, other, null, null, 0, 1, 2, 3}));

		Object[] t = pool.share(Call.DRAW_TEMP, new Object[]{null, null, actor(5), null, 0, 1, 2, 3});
		Object[] s = pool.share(Call.PRE_SCENE_DRAW, new Object[]{null, 1f, 2f, 3f, 4f, 5f, 0, 0, 0, new HashSet<>(Arrays.asList(1, 2))});
		pool.endFrame();
		assertSame(t, pool.share(Call.DRAW_TEMP, new Object[]{null, null, actor(5), null, 0, 1, 2, 3}));
		assertNotSame(t, pool.share(Call.DRAW_TEMP, new Object[]{null, null, actor(6), null, 0, 1, 2, 3}));
		assertSame(s, pool.share(Call.PRE_SCENE_DRAW, new Object[]{null, 1f, 2f, 3f, 4f, 5f, 0, 0, 0, new HashSet<>(Arrays.asList(2, 1))}));
	}

	@Test
	public void aCallIsOnlySharedWithThePreviousFrame()
	{
		CallPool pool = new CallPool();
		Object[] a = pool.share(Call.DRAW_PASS, new Object[]{null, null, 1});
		pool.endFrame();
		pool.endFrame(); // a frame without the call
		assertNotSame(a, pool.share(Call.DRAW_PASS, new Object[]{null, null, 1}));
	}

	private static StandIns.StandInGameObject actor(int x)
	{
		StandIns.StandInGameObject g = new StandIns.StandInGameObject();
		g.hash = 77;
		g.x = x;
		g.min = new net.runelite.api.Point(1, 2);
		g.max = new net.runelite.api.Point(1, 2);
		StandIns.StandInRenderable r = new StandIns.StandInRenderable();
		r.height = 100;
		g.renderable = r;
		return g;
	}
}
