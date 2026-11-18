package com.renderreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FrameStoreLayerTest
{
	// The client's pixel array is one longer than width x height; the layer must still be coded as changes and come
	// back exactly, at the client's length, so playback can paint it.
	@Test
	public void layerWithAPixelPastTheLastRowIsKeptAsChangesAndPlaysBack()
	{
		int w = 200, h = 150, n = w * h + 1, frames = 90;
		int[][] layers = new int[frames][];
		Random rnd = new Random(7);
		int[] layer = new int[n];
		for (int y = 100; y < 150; y++)
		{
			for (int x = 0; x < 120; x++)
			{
				layer[y * w + x] = 0xFF000000 | rnd.nextInt(1 << 24);
			}
		}
		FrameStore st = new FrameStore(Presets.ALL[0], new com.google.gson.Gson(), id -> null);
		for (int f = 0; f < frames; f++)
		{
			// a click cross moving about, and a chat line appearing
			int cx = 20 + f, cy = 30 + f % 40;
			for (int d = -3; d <= 3; d++)
			{
				layer[(cy + d) * w + cx + d] = 0xFFFF0000;
				layer[(cy - d) * w + cx + d] = 0xFFFF0000;
			}
			if (f % 30 == 10)
			{
				Arrays.fill(layer, 140 * w, 140 * w + 100, 0xFF0000FF);
			}
			layers[f] = layer.clone();
			st.append(f * 20L, f / 30, 512, f, null, 0f, null, new ArrayList<>(), layer.clone(), w, null);
		}
		st.finish();
		assertTrue("2D layer kept as changes, not a whole image every frame: " + st.uiBytes(), st.uiBytes() < frames * 2000L);
		for (int i = 0; i < frames; i++)
		{
			int[] got = st.ui(i);
			assertEquals("frame " + i + " length", n, got.length);
			assertArrayEquals("frame " + i, Arrays.copyOf(layers[i], w * h), Arrays.copyOf(got, w * h));
		}
		int[] seek = st.ui(frames / 2).clone();
		assertArrayEquals(Arrays.copyOf(layers[frames / 2], w * h), Arrays.copyOf(seek, w * h));
	}
}
