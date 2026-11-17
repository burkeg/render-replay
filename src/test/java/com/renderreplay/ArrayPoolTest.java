package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ArrayPoolTest
{
	@Test
	public void equalArraysBecomeOneAndAreCountedOnce()
	{
		ArrayPool pool = new ArrayPool();
		int[] a = pool.share(new int[]{1, 2, 3});
		assertEquals(16 + 12, pool.takeAdded());
		assertSame(a, pool.share(new int[]{1, 2, 3}));
		assertEquals(0, pool.takeAdded());

		assertNotSame(a, pool.share(new int[]{1, 2, 4}));
		assertNotSame(a, pool.share(new int[]{1, 2}));
		assertNull(pool.share((int[]) null));
	}

	@Test
	public void everyElementTypeIsShared()
	{
		ArrayPool pool = new ArrayPool();
		float[] f = pool.share(new float[]{1.5f, 2});
		short[] s = pool.share(new short[]{-1, 4});
		byte[] b = pool.share(new byte[]{9});
		assertSame(f, pool.share(new float[]{1.5f, 2}));
		assertSame(s, pool.share(new short[]{-1, 4}));
		assertSame(b, pool.share(new byte[]{9}));
		// same length and hash, another type: must not be handed out as the wrong kind of array
		int[] zeros = pool.share(new int[2]);
		float[] floatZeros = pool.share(new float[2]);
		assertSame(zeros, pool.share(new int[2]));
		assertSame(floatZeros, pool.share(new float[2]));
	}
}
