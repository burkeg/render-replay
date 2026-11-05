package com.renderreplay;

// Generated from the RuneLite API interfaces. Every abstract method is implemented explicitly; only the getters the
// GPU plugin reads return recorded values, the rest answer a neutral default.

final class StandIns
{
	private StandIns()
	{
	}

	/** A Model rebuilt from a recording file: answers what the GPU plugin reads, defaults for the rest. */
	static final class StandInModel implements net.runelite.api.Model
	{
		int vn, fn, rad, dia, bufferOffset, uvBufferOffset, sceneId;
		float[] vx, vy, vz;
		int[] f1, f2, f3, c1, c2, c3, t1, t2, t3;
		byte[] tr, pri, fb, tf;
		short[] ft;
		byte tp, ov0, ov1, ov2, ov3;

		@Override
		public int getVerticesCount()
		{
			return vn;
		}

		@Override
		public float[] getVerticesX()
		{
			return vx;
		}

		@Override
		public float[] getVerticesY()
		{
			return vy;
		}

		@Override
		public float[] getVerticesZ()
		{
			return vz;
		}

		@Override
		public int getFaceCount()
		{
			return fn;
		}

		@Override
		public int[] getFaceIndices1()
		{
			return f1;
		}

		@Override
		public int[] getFaceIndices2()
		{
			return f2;
		}

		@Override
		public int[] getFaceIndices3()
		{
			return f3;
		}

		@Override
		public byte[] getFaceTransparencies()
		{
			return tr;
		}

		@Override
		public short[] getFaceTextures()
		{
			return ft;
		}

		@Override
		public net.runelite.api.Model rotateY90Ccw()
		{
			return null;
		}

		@Override
		public net.runelite.api.Model rotateY180Ccw()
		{
			return null;
		}

		@Override
		public net.runelite.api.Model rotateY270Ccw()
		{
			return null;
		}

		@Override
		public net.runelite.api.Model translate(int p0, int p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Model scale(int p0, int p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Node getNext()
		{
			return null;
		}

		@Override
		public net.runelite.api.Node getPrevious()
		{
			return null;
		}

		@Override
		public long getHash()
		{
			return 0L;
		}

		@Override
		public net.runelite.api.Model getModel()
		{
			return null;
		}

		@Override
		public int getModelHeight()
		{
			return 0;
		}

		@Override
		public void setModelHeight(int p0)
		{
			
		}

		@Override
		public int getAnimationHeightOffset()
		{
			return 0;
		}

		@Override
		public int getRenderMode()
		{
			return 0;
		}

		@Override
		public int[] getFaceColors1()
		{
			return c1;
		}

		@Override
		public int[] getFaceColors2()
		{
			return c2;
		}

		@Override
		public int[] getFaceColors3()
		{
			return c3;
		}

		@Override
		public short[] getUnlitFaceColors()
		{
			return null;
		}

		@Override
		public int getSceneId()
		{
			return sceneId;
		}

		@Override
		public void setSceneId(int p0)
		{
			sceneId = p0;
		}

		@Override
		public int getBufferOffset()
		{
			return bufferOffset;
		}

		@Override
		public void setBufferOffset(int p0)
		{
			bufferOffset = p0;
		}

		@Override
		public int getUvBufferOffset()
		{
			return uvBufferOffset;
		}

		@Override
		public void setUvBufferOffset(int p0)
		{
			uvBufferOffset = p0;
		}

		@Override
		public int getBottomY()
		{
			return 0;
		}

		@Override
		public void calculateBoundsCylinder()
		{
			
		}

		@Override
		public byte getTransparency()
		{
			return tp;
		}

		@Override
		public byte[] getFaceRenderPriorities()
		{
			return pri;
		}

		@Override
		public byte[] getFaceBias()
		{
			return fb;
		}

		@Override
		public int getRadius()
		{
			return rad;
		}

		@Override
		public int getDiameter()
		{
			return dia;
		}

		@Override
		public void calculateExtreme(int p0)
		{
			
		}

		@Override
		public net.runelite.api.AABB getAABB(int p0)
		{
			return null;
		}

		@Override
		public int getXYZMag()
		{
			return 0;
		}

		@Override
		public boolean useBoundingBox()
		{
			return false;
		}

		@Override
		public int[] getVertexNormalsX()
		{
			return null;
		}

		@Override
		public int[] getVertexNormalsY()
		{
			return null;
		}

		@Override
		public int[] getVertexNormalsZ()
		{
			return null;
		}

		@Override
		public byte getOverrideAmount()
		{
			return ov0;
		}

		@Override
		public byte getOverrideHue()
		{
			return ov1;
		}

		@Override
		public byte getOverrideSaturation()
		{
			return ov2;
		}

		@Override
		public byte getOverrideLuminance()
		{
			return ov3;
		}

		@Override
		public byte[] getTextureFaces()
		{
			return tf;
		}

		@Override
		public int[] getTexIndices1()
		{
			return t1;
		}

		@Override
		public int[] getTexIndices2()
		{
			return t2;
		}

		@Override
		public int[] getTexIndices3()
		{
			return t3;
		}

		@Override
		public net.runelite.api.Model getUnskewedModel()
		{
			return null;
		}

		@Override
		public void drawFrustum(int p0, int p1, int p2, int p3, int p4, int p5, int p6)
		{
			
		}

		@Override
		public void drawOrtho(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7)
		{
			
		}
	}

	/** A SceneTileModel rebuilt from a recording file. */
	static final class StandInTileModel implements net.runelite.api.SceneTileModel
	{
		int[] vx, vy, vz, f1, f2, f3, c1, c2, c3, tex;
		boolean flat;
		int bufferOffset, uvBufferOffset, bufferLen;

		@Override
		public int getModelUnderlay()
		{
			return 0;
		}

		@Override
		public int getModelOverlay()
		{
			return 0;
		}

		@Override
		public int getShape()
		{
			return 0;
		}

		@Override
		public int getRotation()
		{
			return 0;
		}

		@Override
		public int[] getFaceX()
		{
			return f1;
		}

		@Override
		public int[] getFaceY()
		{
			return f2;
		}

		@Override
		public int[] getFaceZ()
		{
			return f3;
		}

		@Override
		public int[] getVertexX()
		{
			return vx;
		}

		@Override
		public int[] getVertexY()
		{
			return vy;
		}

		@Override
		public int[] getVertexZ()
		{
			return vz;
		}

		@Override
		public int[] getTriangleColorA()
		{
			return c1;
		}

		@Override
		public int[] getTriangleColorB()
		{
			return c2;
		}

		@Override
		public int[] getTriangleColorC()
		{
			return c3;
		}

		@Override
		public int[] getTriangleTextureId()
		{
			return tex;
		}

		@Override
		public boolean isFlat()
		{
			return flat;
		}

		@Override
		public int getBufferOffset()
		{
			return bufferOffset;
		}

		@Override
		public void setBufferOffset(int p0)
		{
			bufferOffset = p0;
		}

		@Override
		public int getUvBufferOffset()
		{
			return uvBufferOffset;
		}

		@Override
		public void setUvBufferOffset(int p0)
		{
			uvBufferOffset = p0;
		}

		@Override
		public int getBufferLen()
		{
			return bufferLen;
		}

		@Override
		public void setBufferLen(int p0)
		{
			bufferLen = p0;
		}
	}

	/** A SceneTilePaint rebuilt from a recording file. */
	static final class StandInPaint implements net.runelite.api.SceneTilePaint
	{
		int rbg, sw, se, nw, ne, texture, bufferOffset, uvBufferOffset, bufferLen;
		boolean flat;

		@Override
		public int getRBG()
		{
			return rbg;
		}

		@Override
		public int getSwColor()
		{
			return sw;
		}

		@Override
		public void setSwColor(int p0)
		{
			sw = p0;
		}

		@Override
		public int getSeColor()
		{
			return se;
		}

		@Override
		public void setSeColor(int p0)
		{
			se = p0;
		}

		@Override
		public int getNwColor()
		{
			return nw;
		}

		@Override
		public void setNwColor(int p0)
		{
			nw = p0;
		}

		@Override
		public int getNeColor()
		{
			return ne;
		}

		@Override
		public void setNeColor(int p0)
		{
			ne = p0;
		}

		@Override
		public int getTexture()
		{
			return texture;
		}

		@Override
		public void setTexture(int p0)
		{
			texture = p0;
		}

		@Override
		public boolean isFlat()
		{
			return flat;
		}

		@Override
		public int getBufferOffset()
		{
			return bufferOffset;
		}

		@Override
		public void setBufferOffset(int p0)
		{
			bufferOffset = p0;
		}

		@Override
		public int getUvBufferOffset()
		{
			return uvBufferOffset;
		}

		@Override
		public void setUvBufferOffset(int p0)
		{
			uvBufferOffset = p0;
		}

		@Override
		public int getBufferLen()
		{
			return bufferLen;
		}

		@Override
		public void setBufferLen(int p0)
		{
			bufferLen = p0;
		}
	}

	/** A Renderable with the recorded model height and render mode. */
	static final class StandInRenderable implements net.runelite.api.Renderable
	{
		int height, mode;
		net.runelite.api.Model model;

		@Override
		public net.runelite.api.Node getNext()
		{
			return null;
		}

		@Override
		public net.runelite.api.Node getPrevious()
		{
			return null;
		}

		@Override
		public long getHash()
		{
			return 0L;
		}

		@Override
		public net.runelite.api.Model getModel()
		{
			return model;
		}

		@Override
		public int getModelHeight()
		{
			return height;
		}

		@Override
		public void setModelHeight(int p0)
		{
			height = p0;
		}

		@Override
		public int getAnimationHeightOffset()
		{
			return 0;
		}

		@Override
		public int getRenderMode()
		{
			return mode;
		}
	}

	/** A TileObject with the recorded id, plane and hash. */
	static final class StandInTileObject implements net.runelite.api.TileObject
	{
		int id, plane;
		long hash;

		@Override
		public long getHash()
		{
			return hash;
		}

		@Override
		public int getX()
		{
			return 0;
		}

		@Override
		public int getY()
		{
			return 0;
		}

		@Override
		public int getZ()
		{
			return 0;
		}

		@Override
		public int getPlane()
		{
			return plane;
		}

		@Override
		public net.runelite.api.WorldView getWorldView()
		{
			return null;
		}

		@Override
		public int getId()
		{
			return id;
		}

		@Override
		public net.runelite.api.coords.WorldPoint getWorldLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.coords.LocalPoint getLocalLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation(int p0)
		{
			return null;
		}

		@Override
		public java.awt.Polygon getCanvasTilePoly()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasTextLocation(java.awt.Graphics2D p0, java.lang.String p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getMinimapLocation()
		{
			return null;
		}

		@Override
		public java.awt.Shape getClickbox()
		{
			return null;
		}

		@Override
		public java.lang.String getOpOverride(int p0)
		{
			return null;
		}

		@Override
		public boolean isOpShown(int p0)
		{
			return false;
		}
	}

	/** A Tile whose contents were copied when the scene was loaded (before the GPU plugin consumed the real one). */
	static final class StandInTile implements net.runelite.api.Tile
	{
		net.runelite.api.SceneTilePaint paint;
		net.runelite.api.SceneTileModel model;
		net.runelite.api.WallObject wall;
		net.runelite.api.GroundObject ground;
		net.runelite.api.DecorativeObject deco;
		net.runelite.api.GameObject[] games;
		net.runelite.api.Tile bridge;
		int plane, renderLevel;
		net.runelite.api.Point loc;

		@Override
		public net.runelite.api.DecorativeObject getDecorativeObject()
		{
			return deco;
		}

		@Override
		public net.runelite.api.GameObject[] getGameObjects()
		{
			return games;
		}

		@Override
		public net.runelite.api.ItemLayer getItemLayer()
		{
			return null;
		}

		@Override
		public net.runelite.api.GroundObject getGroundObject()
		{
			return ground;
		}

		@Override
		public void setGroundObject(net.runelite.api.GroundObject p0)
		{
			ground = p0;
		}

		@Override
		public net.runelite.api.WallObject getWallObject()
		{
			return wall;
		}

		@Override
		public net.runelite.api.SceneTilePaint getSceneTilePaint()
		{
			return paint;
		}

		@Override
		public void setSceneTilePaint(net.runelite.api.SceneTilePaint p0)
		{
			paint = p0;
		}

		@Override
		public net.runelite.api.SceneTileModel getSceneTileModel()
		{
			return model;
		}

		@Override
		public void setSceneTileModel(net.runelite.api.SceneTileModel p0)
		{
			model = p0;
		}

		@Override
		public net.runelite.api.coords.WorldPoint getWorldLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getSceneLocation()
		{
			return loc;
		}

		@Override
		public net.runelite.api.coords.LocalPoint getLocalLocation()
		{
			return null;
		}

		@Override
		public int getPlane()
		{
			return plane;
		}

		@Override
		public int getRenderLevel()
		{
			return renderLevel;
		}

		@Override
		public java.util.List<net.runelite.api.TileItem> getGroundItems()
		{
			return null;
		}

		@Override
		public net.runelite.api.Tile getBridge()
		{
			return bridge;
		}
	}

	/** A Scene copied at load time: the tile arrays hold StandInTiles, so the GPU plugin can be given the same scene again after it consumed the real one. */
	static final class StandInScene implements net.runelite.api.Scene
	{
		net.runelite.api.Tile[][][] tiles, extended;
		byte[][][] settings, shapes;
		int[][][] roofs, heights, chunks;
		short[][][] underlay, overlay;
		int[] regions;
		int baseX, baseY, worldViewId, drawDistance, minLevel, roofRemovalMode;
		boolean instance;
		byte oAmount, oHue, oSat, oLum;
		net.runelite.api.Model skybox;

		@Override
		public net.runelite.api.Node getNext()
		{
			return null;
		}

		@Override
		public net.runelite.api.Node getPrevious()
		{
			return null;
		}

		@Override
		public long getHash()
		{
			return 0L;
		}

		@Override
		public net.runelite.api.Model getModel()
		{
			return null;
		}

		@Override
		public int getModelHeight()
		{
			return 0;
		}

		@Override
		public void setModelHeight(int p0)
		{
			
		}

		@Override
		public int getAnimationHeightOffset()
		{
			return 0;
		}

		@Override
		public int getRenderMode()
		{
			return 0;
		}

		@Override
		public net.runelite.api.Tile[][][] getTiles()
		{
			return tiles;
		}

		@Override
		public net.runelite.api.Tile[][][] getExtendedTiles()
		{
			return extended;
		}

		@Override
		public byte[][][] getExtendedTileSettings()
		{
			return settings;
		}

		@Override
		public int getDrawDistance()
		{
			return drawDistance;
		}

		@Override
		public void setDrawDistance(int p0)
		{
			drawDistance = p0;
		}

		@Override
		public int getWorldViewId()
		{
			return worldViewId;
		}

		@Override
		public int getMinLevel()
		{
			return minLevel;
		}

		@Override
		public void setMinLevel(int p0)
		{
			minLevel = p0;
		}

		@Override
		public void removeTile(net.runelite.api.Tile p0)
		{
			// the GPU plugin calls this after uploading a tile: a snapshot must keep its tiles
		}

		@Override
		public void removeGameObject(net.runelite.api.GameObject p0)
		{
			// see removeTile
		}

		@Override
		public void buildRoofs()
		{
			
		}

		@Override
		public int[][][] getRoofs()
		{
			return roofs;
		}

		@Override
		public void setRoofRemovalMode(int p0)
		{
			roofRemovalMode = p0;
		}

		@Override
		public int getRoofRemovalMode()
		{
			return roofRemovalMode;
		}

		@Override
		public short[][][] getUnderlayIds()
		{
			return underlay;
		}

		@Override
		public short[][][] getOverlayIds()
		{
			return overlay;
		}

		@Override
		public byte[][][] getTileShapes()
		{
			return shapes;
		}

		@Override
		public int[][][] getTileHeights()
		{
			return heights;
		}

		@Override
		public int getBaseX()
		{
			return baseX;
		}

		@Override
		public int getBaseY()
		{
			return baseY;
		}

		@Override
		public boolean isInstance()
		{
			return instance;
		}

		@Override
		public int[][][] getInstanceTemplateChunks()
		{
			return chunks;
		}

		@Override
		public int[] getMapRegions()
		{
			return regions;
		}

		@Override
		public byte getOverrideAmount()
		{
			return oAmount;
		}

		@Override
		public byte getOverrideHue()
		{
			return oHue;
		}

		@Override
		public byte getOverrideSaturation()
		{
			return oSat;
		}

		@Override
		public byte getOverrideLuminance()
		{
			return oLum;
		}

		@Override
		public net.runelite.api.Model getSkybox()
		{
			return skybox;
		}
	}

	/** A WallObject copied at scene load time. */
	static final class StandInWallObject implements net.runelite.api.WallObject
	{
		int id, x, y, z, plane, oriA, oriB, config;
		long hash;
		net.runelite.api.Renderable r1, r2;

		@Override
		public long getHash()
		{
			return hash;
		}

		@Override
		public int getX()
		{
			return x;
		}

		@Override
		public int getY()
		{
			return y;
		}

		@Override
		public int getZ()
		{
			return z;
		}

		@Override
		public int getPlane()
		{
			return plane;
		}

		@Override
		public net.runelite.api.WorldView getWorldView()
		{
			return null;
		}

		@Override
		public int getId()
		{
			return id;
		}

		@Override
		public net.runelite.api.coords.WorldPoint getWorldLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.coords.LocalPoint getLocalLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation(int p0)
		{
			return null;
		}

		@Override
		public java.awt.Polygon getCanvasTilePoly()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasTextLocation(java.awt.Graphics2D p0, java.lang.String p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getMinimapLocation()
		{
			return null;
		}

		@Override
		public java.awt.Shape getClickbox()
		{
			return null;
		}

		@Override
		public java.lang.String getOpOverride(int p0)
		{
			return null;
		}

		@Override
		public boolean isOpShown(int p0)
		{
			return false;
		}

		@Override
		public int getOrientationA()
		{
			return oriA;
		}

		@Override
		public int getOrientationB()
		{
			return oriB;
		}

		@Override
		public int getConfig()
		{
			return config;
		}

		@Override
		public java.awt.Shape getConvexHull()
		{
			return null;
		}

		@Override
		public java.awt.Shape getConvexHull2()
		{
			return null;
		}

		@Override
		public net.runelite.api.Renderable getRenderable1()
		{
			return r1;
		}

		@Override
		public net.runelite.api.Renderable getRenderable2()
		{
			return r2;
		}
	}

	/** A DecorativeObject copied at scene load time. */
	static final class StandInDecorativeObject implements net.runelite.api.DecorativeObject
	{
		int id, x, y, z, plane, xo, yo, xo2, yo2, config;
		long hash;
		net.runelite.api.Renderable r1, r2;

		@Override
		public long getHash()
		{
			return hash;
		}

		@Override
		public int getX()
		{
			return x;
		}

		@Override
		public int getY()
		{
			return y;
		}

		@Override
		public int getZ()
		{
			return z;
		}

		@Override
		public int getPlane()
		{
			return plane;
		}

		@Override
		public net.runelite.api.WorldView getWorldView()
		{
			return null;
		}

		@Override
		public int getId()
		{
			return id;
		}

		@Override
		public net.runelite.api.coords.WorldPoint getWorldLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.coords.LocalPoint getLocalLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation(int p0)
		{
			return null;
		}

		@Override
		public java.awt.Polygon getCanvasTilePoly()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasTextLocation(java.awt.Graphics2D p0, java.lang.String p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getMinimapLocation()
		{
			return null;
		}

		@Override
		public java.awt.Shape getClickbox()
		{
			return null;
		}

		@Override
		public java.lang.String getOpOverride(int p0)
		{
			return null;
		}

		@Override
		public boolean isOpShown(int p0)
		{
			return false;
		}

		@Override
		public java.awt.Shape getConvexHull()
		{
			return null;
		}

		@Override
		public java.awt.Shape getConvexHull2()
		{
			return null;
		}

		@Override
		public net.runelite.api.Renderable getRenderable()
		{
			return r1;
		}

		@Override
		public net.runelite.api.Renderable getRenderable2()
		{
			return r2;
		}

		@Override
		public int getXOffset()
		{
			return xo;
		}

		@Override
		public int getYOffset()
		{
			return yo;
		}

		@Override
		public int getXOffset2()
		{
			return xo2;
		}

		@Override
		public int getYOffset2()
		{
			return yo2;
		}

		@Override
		public int getConfig()
		{
			return config;
		}
	}

	/** A GroundObject copied at scene load time. */
	static final class StandInGroundObject implements net.runelite.api.GroundObject
	{
		int id, x, y, z, plane, config;
		long hash;
		net.runelite.api.Renderable r1;

		@Override
		public long getHash()
		{
			return hash;
		}

		@Override
		public int getX()
		{
			return x;
		}

		@Override
		public int getY()
		{
			return y;
		}

		@Override
		public int getZ()
		{
			return z;
		}

		@Override
		public int getPlane()
		{
			return plane;
		}

		@Override
		public net.runelite.api.WorldView getWorldView()
		{
			return null;
		}

		@Override
		public int getId()
		{
			return id;
		}

		@Override
		public net.runelite.api.coords.WorldPoint getWorldLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.coords.LocalPoint getLocalLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation(int p0)
		{
			return null;
		}

		@Override
		public java.awt.Polygon getCanvasTilePoly()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasTextLocation(java.awt.Graphics2D p0, java.lang.String p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getMinimapLocation()
		{
			return null;
		}

		@Override
		public java.awt.Shape getClickbox()
		{
			return null;
		}

		@Override
		public java.lang.String getOpOverride(int p0)
		{
			return null;
		}

		@Override
		public boolean isOpShown(int p0)
		{
			return false;
		}

		@Override
		public net.runelite.api.Renderable getRenderable()
		{
			return r1;
		}

		@Override
		public java.awt.Shape getConvexHull()
		{
			return null;
		}

		@Override
		public int getConfig()
		{
			return config;
		}
	}

	/** An animated object as it was at scene load time: the GPU plugin only asks it for its z-buffer model when it builds the zone (the live object throws for a scene the client has left). */
	static final class StandInDynamicObject implements net.runelite.api.DynamicObject
	{
		int height, mode, animFrame, animCycle;
		net.runelite.api.Model zbuf;

		@Override
		public net.runelite.api.Node getNext()
		{
			return null;
		}

		@Override
		public net.runelite.api.Node getPrevious()
		{
			return null;
		}

		@Override
		public long getHash()
		{
			return 0L;
		}

		@Override
		public net.runelite.api.Model getModel()
		{
			return null;
		}

		@Override
		public int getModelHeight()
		{
			return height;
		}

		@Override
		public void setModelHeight(int p0)
		{
			
		}

		@Override
		public int getAnimationHeightOffset()
		{
			return 0;
		}

		@Override
		public int getRenderMode()
		{
			return mode;
		}

		@Override
		public net.runelite.api.Animation getAnimation()
		{
			return null;
		}

		@Override
		public int getAnimFrame()
		{
			return animFrame;
		}

		@Override
		public int getAnimCycle()
		{
			return animCycle;
		}

		@Override
		public net.runelite.api.Model getModelZbuf()
		{
			return zbuf;
		}

		@Override
		public net.runelite.api.ObjectComposition getRecordedObjectComposition()
		{
			return null;
		}
	}

	/** The GameObject wrapper drawTemp reads, rebuilt from a recording file. */
	static final class StandInGameObject implements net.runelite.api.GameObject
	{
		int id, plane, x, y, z, orientation, modelOrientation, config, sizeX, sizeY;
		long hash;
		net.runelite.api.Point min, max;
		net.runelite.api.Renderable renderable;
		net.runelite.api.WorldView wv;

		@Override
		public long getHash()
		{
			return hash;
		}

		@Override
		public int getX()
		{
			return x;
		}

		@Override
		public int getY()
		{
			return y;
		}

		@Override
		public int getZ()
		{
			return z;
		}

		@Override
		public int getPlane()
		{
			return plane;
		}

		@Override
		public net.runelite.api.WorldView getWorldView()
		{
			return wv;
		}

		@Override
		public int getId()
		{
			return id;
		}

		@Override
		public net.runelite.api.coords.WorldPoint getWorldLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.coords.LocalPoint getLocalLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasLocation(int p0)
		{
			return null;
		}

		@Override
		public java.awt.Polygon getCanvasTilePoly()
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getCanvasTextLocation(java.awt.Graphics2D p0, java.lang.String p1, int p2)
		{
			return null;
		}

		@Override
		public net.runelite.api.Point getMinimapLocation()
		{
			return null;
		}

		@Override
		public java.awt.Shape getClickbox()
		{
			return null;
		}

		@Override
		public java.lang.String getOpOverride(int p0)
		{
			return null;
		}

		@Override
		public boolean isOpShown(int p0)
		{
			return false;
		}

		@Override
		public int sizeX()
		{
			return sizeX;
		}

		@Override
		public int sizeY()
		{
			return sizeY;
		}

		@Override
		public net.runelite.api.Point getSceneMinLocation()
		{
			return min;
		}

		@Override
		public net.runelite.api.Point getSceneMaxLocation()
		{
			return max;
		}

		@Override
		public java.awt.Shape getConvexHull()
		{
			return null;
		}

		@Override
		public int getOrientation()
		{
			return orientation;
		}

		@Override
		public net.runelite.api.Renderable getRenderable()
		{
			return renderable;
		}

		@Override
		public int getModelOrientation()
		{
			return modelOrientation;
		}

		@Override
		public int getConfig()
		{
			return config;
		}
	}

}
