package com.renderreplay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class UiFramesTest
{
	private static int[] noisy(Random r, int[] base, int changes)
	{
		int[] a = base.clone();
		for (int i = 0; i < changes; i++)
		{
			a[r.nextInt(a.length)] = r.nextInt();
		}
		return a;
	}

	@Test
	public void deltaChainReconstructsEveryFrameExactly()
	{
		Random r = new Random(7);
		int[] f0 = noisy(r, new int[50_000], 8000);
		int[] f1 = noisy(r, f0, 300);
		int[] f2 = noisy(r, f1, 0);   // identical frame
		int[] f3 = noisy(r, f2, 9000);
		int[][] frames = {f0, f1, f2, f3};

		byte[][] enc = new byte[frames.length][];
		int[] prev = null;
		for (int i = 0; i < frames.length; i++)
		{
			enc[i] = UiFrames.encode(prev, frames[i]);
			prev = frames[i];
		}

		int[] rebuilt = new int[50_000];
		for (int i = 0; i < frames.length; i++)
		{
			assertEquals(50_000, UiFrames.length(enc[i]));
			UiFrames.apply(enc[i], rebuilt);
			assertArrayEquals("frame " + i, frames[i], rebuilt);
		}
		assertTrue("identical frame should be tiny", enc[2].length < 64);
	}

	@Test(expected = IllegalArgumentException.class)
	public void wrongSizedTargetIsRejected()
	{
		UiFrames.apply(UiFrames.encode(null, new int[10]), new int[11]);
	}

	@Test
	public void keyframeMustClearStalePixelsLeftByEarlierFrames()
	{
		int n = 1000;
		int[] a = new int[n];
		a[10] = 0xff00ff00;                  // an overlay pixel that later moves away
		int[] b = new int[n];
		b[500] = 0xff00ff00;                 // same overlay now elsewhere; pixel 10 is transparent again

		byte[] encA = UiFrames.encode(null, a);
		byte[] keyB = UiFrames.encode(null, b);   // keyframes are encoded against zeros
		int[] buf = new int[n];
		UiFrames.applyFrame(encA, buf, true);
		UiFrames.applyFrame(keyB, buf, true);
		assertArrayEquals("no ghost of the old overlay position", b, buf);

		// documents the bug this guards against: plain apply() on a keyframe leaves a ghost
		int[] naive = new int[n];
		UiFrames.apply(encA, naive);
		UiFrames.apply(keyB, naive);
		assertEquals("the naive path keeps the stale pixel", 0xff00ff00, naive[10]);
	}
}
