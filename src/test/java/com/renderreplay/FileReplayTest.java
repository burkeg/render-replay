package com.renderreplay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import org.junit.Test;

public class FileReplayTest
{
	private static JsonObject json(String s)
	{
		return new JsonParser().parse(s).getAsJsonObject();
	}

	@Test
	public void modelStandInAnswersEveryGetterTheGpuPluginReads()
	{
		Model m = FileReplay.model(json("{\"id\":5,\"kind\":\"model\",\"vn\":3,\"fn\":1,\"vx\":[1.5,2,3],\"vy\":[0,0,0],\"vz\":[4,5,6],"
			+ "\"f1\":[0],\"f2\":[1],\"f3\":[2],\"c1\":[100],\"c2\":[101],\"c3\":[102],\"tr\":[0],\"pri\":[3],\"ft\":[-1],\"fb\":[7],"
			+ "\"ov\":[1,2,3,4],\"tp\":9,\"tf\":[1],\"t1\":[0],\"t2\":[1],\"t3\":[2],\"rad\":50,\"dia\":100}"));
		assertEquals(3, m.getVerticesCount());
		assertEquals(1, m.getFaceCount());
		assertArrayEquals(new float[]{1.5f, 2, 3}, m.getVerticesX(), 0);
		assertArrayEquals(new int[]{102}, m.getFaceColors3());
		assertArrayEquals(new byte[]{3}, m.getFaceRenderPriorities());
		assertArrayEquals(new short[]{-1}, m.getFaceTextures());
		assertArrayEquals(new byte[]{7}, m.getFaceBias());
		assertEquals(2, m.getOverrideHue());
		assertEquals(4, m.getOverrideLuminance());
		assertEquals(9, m.getTransparency());
		assertEquals("computed from the vertices, not read from the file", 7, m.getRadius());
		assertEquals(14, m.getDiameter());
		m.setBufferOffset(77);                      // state the GPU plugin writes back
		assertEquals(77, m.getBufferOffset());
		m.calculateBoundsCylinder();                // not saved: harmless no-op
		assertEquals(0, m.getXYZMag());             // unknown getters answer 0 instead of throwing
	}

	@Test
	public void copyOfFreezesALiveModelWithoutSharingItsArrays()
	{
		StandIns.StandInModel live = new StandIns.StandInModel();
		live.vn = 2;
		live.fn = 1;
		live.vx = new float[]{1, 2, 99, 99};        // the client hands back oversized backing arrays
		live.vy = new float[]{3, 4, 99, 99};
		live.vz = new float[]{5, 6, 99, 99};
		live.f1 = new int[]{0, 99};
		live.f2 = new int[]{1, 99};
		live.f3 = new int[]{0, 99};
		live.c1 = new int[]{100, 99};
		live.c2 = new int[]{101, 99};
		live.c3 = new int[]{102, 99};
		live.pri = new byte[]{3, 9};
		live.ft = new short[]{-1, 9};
		live.tf = new byte[]{1, 2, 3};               // per texture face, not per face: kept whole
		live.t1 = new int[]{0, 1, 2};
		live.ov1 = 2;
		live.tp = 9;
		live.rad = 50;
		live.dia = 100;

		Model copy = FileReplay.copyOf(live);
		assertEquals(2, copy.getVerticesCount());
		assertEquals(1, copy.getFaceCount());
		assertArrayEquals(new float[]{1, 2}, copy.getVerticesX(), 0);
		assertArrayEquals(new int[]{102}, copy.getFaceColors3());
		assertArrayEquals(new byte[]{3}, copy.getFaceRenderPriorities());
		assertArrayEquals(new short[]{-1}, copy.getFaceTextures());
		assertArrayEquals(new byte[]{1, 2, 3}, copy.getTextureFaces());
		assertArrayEquals(new int[]{0, 1, 2}, copy.getTexIndices1());
		assertNull(copy.getFaceTransparencies());   // absent arrays stay absent
		assertNull(copy.getTexIndices2());
		assertEquals(2, copy.getOverrideHue());
		assertEquals(9, copy.getTransparency());
		// the live model's own values may not be computed yet: xz radius 7, nothing above the origin, 4 below it
		assertEquals(7, copy.getRadius());
		assertEquals(16, copy.getDiameter());

		live.c1[0] = 7;                              // the client rewrites its model after the frame
		live.vx[0] = 7;
		live.tf[0] = 7;
		assertArrayEquals(new int[]{100}, copy.getFaceColors1());
		assertArrayEquals(new float[]{1, 2}, copy.getVerticesX(), 0);
		assertArrayEquals(new byte[]{1, 2, 3}, copy.getTextureFaces());
	}

	@Test
	public void gameObjectStandInCarriesWhatDrawTempReads()
	{
		GameObject g = FileReplay.gameObject(json("{\"go\":16608,\"hash\":17414815744,\"xyz\":[5504,6016,-160],\"plane\":1,\"ori\":[256,512,3],"
			+ "\"size\":[2,2],\"box\":[42,46,43,47],\"h\":371,\"m\":0}"));
		assertEquals(16608, g.getId());
		assertEquals(17414815744L, g.getHash());
		assertEquals(5504, g.getX());
		assertEquals(-160, g.getZ());
		assertEquals(1, g.getPlane());
		assertEquals(512, g.getModelOrientation());
		assertEquals(2, g.sizeX());
		assertEquals(46, g.getSceneMinLocation().getY());
		assertEquals(47, g.getSceneMaxLocation().getY());
		assertNotNull(g.getRenderable());
		assertEquals(371, g.getRenderable().getModelHeight());
		assertNull(g.getWorldView());
	}

	@Test
	public void projectionIsRebuiltFromFourSamplePoints()
	{
		// map: out = (x*2 + 10, y*1, z*-1)
		Projection p = FileReplay.projection(json("{\"proj\":\"eu\",\"s\":[[10,0,0],[266,0,0],[10,128,0],[10,0,-128]]}"));
		float[] r = p.project(64, 32, 16);
		assertArrayEquals(new float[]{138, 32, -16}, r, 0.001f);
	}

	@Test
	public void paintAndTileModel()
	{
		com.google.gson.JsonArray c = new JsonParser().parse("[7,1,2,3,4,-1,1]").getAsJsonArray();
		net.runelite.api.SceneTilePaint t = FileReplay.paint(c);
		assertEquals(7, t.getRBG());
		assertEquals(3, t.getNwColor());
		assertTrue(t.isFlat());
		net.runelite.api.SceneTileModel tm = FileReplay.tileModel(json("{\"id\":1,\"vx\":[0,128],\"vy\":[0,0],\"vz\":[0,128],\"f1\":[0],\"f2\":[1],\"f3\":[0],\"c1\":[1],\"c2\":[2],\"c3\":[3],\"tex\":[-1],\"flat\":true}"));
		assertArrayEquals(new int[]{0, 128}, tm.getVertexX());
		assertTrue(tm.isFlat());
	}
}
