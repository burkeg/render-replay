package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class DrawDeltaTest
{
	private static List<String> elements(String arrayJson)
	{
		JsonArray a = new JsonParser().parse(arrayJson).getAsJsonArray();
		List<String> out = new ArrayList<>();
		for (JsonElement e : a)
		{
			out.add(e.toString());
		}
		return out;
	}

	@Test
	public void roundTripsExactlyAcrossManyRandomFrames()
	{
		Random rnd = new Random(7);
		DrawDelta enc = new DrawDelta();
		List<String> prevDecoded = new ArrayList<>();
		List<String> pool = new ArrayList<>();
		for (int i = 0; i < 60; i++)
		{
			pool.add("[\"drawScenePaint\",{\"scene\":0}," + i + "," + rnd.nextInt(5) + "]");
		}
		for (int f = 0; f < 400; f++)
		{
			List<String> rows = new ArrayList<>();
			int n = rnd.nextInt(80);
			for (int i = 0; i < n; i++)
			{
				rows.add(rnd.nextInt(10) == 0 ? "[\"x\"," + rnd.nextInt(1000) + "]" : pool.get(rnd.nextInt(pool.size())));
			}
			List<String> decoded = DrawDelta.decode(elements(enc.encode(rows)), prevDecoded);
			assertEquals("frame " + f, rows, decoded);
			prevDecoded = decoded;
		}
	}

	@Test
	public void identicalFramesShrinkToARunReference()
	{
		DrawDelta enc = new DrawDelta();
		List<String> rows = new ArrayList<>();
		for (int i = 0; i < 300; i++)
		{
			rows.add("[\"drawScenePaint\",{\"scene\":0},[0,1,2,3],0," + i + ",80]");
		}
		String key = enc.encode(rows);
		String delta = enc.encode(rows);
		assertTrue(key.length() > 10_000);
		assertEquals("[{\"p\":[0,300]}]", delta);
	}

	@Test
	public void emptyAndFirstFramesAreFine()
	{
		DrawDelta enc = new DrawDelta();
		assertEquals("[]", enc.encode(new ArrayList<>()));
		assertEquals("[[\"a\"]]", enc.encode(Arrays.asList("[\"a\"]")));
	}
}
