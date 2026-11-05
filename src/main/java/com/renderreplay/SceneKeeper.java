package com.renderreplay;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;

/**
 * Keeps a copy of the scenes the client loads. The GPU plugin consumes a Scene while uploading it, so loading the same
 * Scene object a second time uploads almost nothing. A copy that holds the same paint, tile model and object references
 * can be loaded again to rebuild the zones of a scene the client has left. Read-only on the client's objects; the newest
 * MAX scenes are kept.
 */
final class SceneKeeper
{
	static final int MAX = 8;

	private final Map<Scene, StandIns.StandInScene> copies = new IdentityHashMap<>();
	private final List<Scene> order = new ArrayList<>();

	/** Copies the scene's data, replacing an earlier copy of the same scene. */
	synchronized StandIns.StandInScene capture(Scene sc)
	{
		StandIns.StandInScene c = new StandIns.StandInScene();
		c.baseX = sc.getBaseX();
		c.baseY = sc.getBaseY();
		c.worldViewId = sc.getWorldViewId();
		c.instance = sc.isInstance();
		c.chunks = sc.getInstanceTemplateChunks();
		c.roofs = sc.getRoofs();
		c.heights = sc.getTileHeights();
		c.settings = sc.getExtendedTileSettings();
		c.underlay = sc.getUnderlayIds();
		c.overlay = sc.getOverlayIds();
		c.shapes = sc.getTileShapes();
		c.regions = sc.getMapRegions();
		c.oAmount = sc.getOverrideAmount();
		c.oHue = sc.getOverrideHue();
		c.oSat = sc.getOverrideSaturation();
		c.oLum = sc.getOverrideLuminance();
		c.skybox = sc.getSkybox();
		c.drawDistance = sc.getDrawDistance();
		c.minLevel = sc.getMinLevel();
		c.roofRemovalMode = sc.getRoofRemovalMode();
		Ctx ctx = new Ctx();
		c.extended = copyTiles(sc.getExtendedTiles(), ctx);
		c.tiles = copyTiles(sc.getTiles(), ctx);
		if (!copies.containsKey(sc))
		{
			order.add(sc);
		}
		copies.put(sc, c);
		while (order.size() > MAX)
		{
			copies.remove(order.remove(0));
		}
		return c;
	}

	/**
	 * The copy as handed to the GPU plugin by a replay swap. GpuPlugin.loadScene reuses the zones it already holds, shifted
	 * by the base difference between the scene being loaded and the client's live scene: it assumes its zones belong to
	 * the live scene. After a swap of ours they belong to another scene, and the next load would show the wrong area's
	 * terrain. Reuse only happens when the loaded scene's isInstance() equals the live scene's, so this view answers the
	 * opposite and every zone is built from the copy.
	 */
	static StandIns.StandInScene withoutZoneReuse(StandIns.StandInScene c, boolean liveIsInstance)
	{
		StandIns.StandInScene v = new StandIns.StandInScene();
		v.tiles = c.tiles;
		v.extended = c.extended;
		v.settings = c.settings;
		v.shapes = c.shapes;
		v.roofs = c.roofs;
		v.heights = c.heights;
		v.chunks = c.chunks;
		v.underlay = c.underlay;
		v.overlay = c.overlay;
		v.regions = c.regions;
		v.baseX = c.baseX;
		v.baseY = c.baseY;
		v.worldViewId = c.worldViewId;
		v.drawDistance = c.drawDistance;
		v.minLevel = c.minLevel;
		v.roofRemovalMode = c.roofRemovalMode;
		v.oAmount = c.oAmount;
		v.oHue = c.oHue;
		v.oSat = c.oSat;
		v.oLum = c.oLum;
		v.skybox = c.skybox;
		v.instance = !liveIsInstance;
		return v;
	}

	synchronized StandIns.StandInScene get(Scene sc)
	{
		return copies.get(sc);
	}

	synchronized void clear()
	{
		copies.clear();
		order.clear();
	}

	/**
	 * A zone of a scene the client is about to rebuild (invalidateZone call): its tiles have just changed in the live arrays, so
	 * refresh our copy of them, otherwise re-loading this scene later would show the state from load time.
	 */
	synchronized void refreshZone(Scene sc, int zx, int zz)
	{
		StandIns.StandInScene c = copies.get(sc);
		Tile[][][] live = sc.getExtendedTiles();
		Ctx refreshCtx = new Ctx();
		if (c == null || live == null)
		{
			return;
		}
		for (int pl = 0; pl < live.length && pl < c.extended.length; pl++)
		{
			for (int x = zx * 8; x < zx * 8 + 8 && x < live[pl].length; x++)
			{
				for (int y = zz * 8; y < zz * 8 + 8 && y < live[pl][x].length; y++)
				{
					Tile t = live[pl][x][y];
					if (t != null)
					{
						c.extended[pl][x][y] = copyTile(t, 1, refreshCtx);
					}
				}
			}
		}
	}

	/** One copy operation shares its stand-ins: an object that spans several tiles must stay ONE object (the uploader de-duplicates by identity). */
	private static final class Ctx
	{
		final java.util.IdentityHashMap<Object, Object> done = new java.util.IdentityHashMap<>();
	}

	private static Tile[][][] copyTiles(Tile[][][] src)
	{
		return copyTiles(src, new Ctx());
	}

	private static Tile[][][] copyTiles(Tile[][][] src, Ctx ctx)
	{
		if (src == null)
		{
			return null;
		}
		Tile[][][] out = new Tile[src.length][][];
		for (int z = 0; z < src.length; z++)
		{
			out[z] = new Tile[src[z].length][];
			for (int x = 0; x < src[z].length; x++)
			{
				out[z][x] = new Tile[src[z][x].length];
				for (int y = 0; y < src[z][x].length; y++)
				{
					Tile t = src[z][x][y];
					out[z][x][y] = t == null ? null : copyTile(t, 1, ctx);
				}
			}
		}
		return out;
	}

	static StandIns.StandInTile copyTile(Tile t, int bridgeDepth)
	{
		return copyTile(t, bridgeDepth, new Ctx());
	}

	/** Copies what the GPU plugin reads from a tile; a bridge tile is copied one level deep. */
	private static StandIns.StandInTile copyTile(Tile t, int bridgeDepth, Ctx ctx)
	{
		StandIns.StandInTile c = new StandIns.StandInTile();
		c.paint = t.getSceneTilePaint();
		c.model = t.getSceneTileModel();
		c.wall = copyWall(t.getWallObject(), ctx);
		c.ground = copyGround(t.getGroundObject(), ctx);
		c.deco = copyDeco(t.getDecorativeObject(), ctx);
		GameObject[] g = t.getGameObjects();
		if (g != null)
		{
			c.games = new GameObject[g.length];
			for (int i = 0; i < g.length; i++)
			{
				c.games[i] = copyGame(g[i], ctx);
			}
		}
		c.plane = t.getPlane();
		c.renderLevel = t.getRenderLevel();
		c.loc = t.getSceneLocation();
		Tile b = t.getBridge();
		c.bridge = b == null || bridgeDepth <= 0 ? null : copyTile(b, bridgeDepth - 1, ctx);
		return c;
	}

	@SuppressWarnings("unchecked")
	private static <T> T cached(Ctx ctx, Object key)
	{
		return (T) ctx.done.get(key);
	}

	private static net.runelite.api.WallObject copyWall(net.runelite.api.WallObject w, Ctx ctx)
	{
		if (w == null)
		{
			return null;
		}
		net.runelite.api.WallObject hit = cached(ctx, w);
		if (hit != null)
		{
			return hit;
		}
		StandIns.StandInWallObject c = new StandIns.StandInWallObject();
		ctx.done.put(w, c);
		c.id = w.getId();
		c.x = w.getX();
		c.y = w.getY();
		c.z = w.getZ();
		c.plane = w.getPlane();
		c.hash = w.getHash();
		c.oriA = w.getOrientationA();
		c.oriB = w.getOrientationB();
		c.config = w.getConfig();
		c.r1 = copyRenderable(w.getRenderable1(), ctx);
		c.r2 = copyRenderable(w.getRenderable2(), ctx);
		return c;
	}

	private static net.runelite.api.DecorativeObject copyDeco(net.runelite.api.DecorativeObject d, Ctx ctx)
	{
		if (d == null)
		{
			return null;
		}
		net.runelite.api.DecorativeObject hit = cached(ctx, d);
		if (hit != null)
		{
			return hit;
		}
		StandIns.StandInDecorativeObject c = new StandIns.StandInDecorativeObject();
		ctx.done.put(d, c);
		c.id = d.getId();
		c.x = d.getX();
		c.y = d.getY();
		c.z = d.getZ();
		c.plane = d.getPlane();
		c.hash = d.getHash();
		c.xo = d.getXOffset();
		c.yo = d.getYOffset();
		c.xo2 = d.getXOffset2();
		c.yo2 = d.getYOffset2();
		c.config = d.getConfig();
		c.r1 = copyRenderable(d.getRenderable(), ctx);
		c.r2 = copyRenderable(d.getRenderable2(), ctx);
		return c;
	}

	private static net.runelite.api.GroundObject copyGround(net.runelite.api.GroundObject g, Ctx ctx)
	{
		if (g == null)
		{
			return null;
		}
		net.runelite.api.GroundObject hit = cached(ctx, g);
		if (hit != null)
		{
			return hit;
		}
		StandIns.StandInGroundObject c = new StandIns.StandInGroundObject();
		ctx.done.put(g, c);
		c.id = g.getId();
		c.x = g.getX();
		c.y = g.getY();
		c.z = g.getZ();
		c.plane = g.getPlane();
		c.hash = g.getHash();
		c.config = g.getConfig();
		c.r1 = copyRenderable(g.getRenderable(), ctx);
		return c;
	}

	private static GameObject copyGame(GameObject g, Ctx ctx)
	{
		if (g == null)
		{
			return null;
		}
		GameObject hit = cached(ctx, g);
		if (hit != null)
		{
			return hit;
		}
		StandIns.StandInGameObject c = new StandIns.StandInGameObject();
		ctx.done.put(g, c);
		c.id = g.getId();
		c.hash = g.getHash();
		c.x = g.getX();
		c.y = g.getY();
		c.z = g.getZ();
		c.plane = g.getPlane();
		c.orientation = g.getOrientation();
		c.modelOrientation = g.getModelOrientation();
		c.config = g.getConfig();
		c.sizeX = g.sizeX();
		c.sizeY = g.sizeY();
		c.min = g.getSceneMinLocation();
		c.max = g.getSceneMaxLocation();
		c.renderable = copyRenderable(g.getRenderable(), ctx);
		return c;
	}

	/**
	 * A Renderable as the GPU plugin reads it while building a zone. Models are shared as they are; an animated object
	 * (DynamicObject) is replaced by a stand-in holding its z-buffer model from now, because the live object throws once the
	 * client has left its scene (measured: NullPointerException in getModelZbuf on a reload).
	 */
	private static net.runelite.api.Renderable copyRenderable(net.runelite.api.Renderable r, Ctx ctx)
	{
		if (r == null || r instanceof net.runelite.api.Model)
		{
			return r;
		}
		net.runelite.api.Renderable hit = cached(ctx, r);
		if (hit != null)
		{
			return hit;
		}
		net.runelite.api.Renderable out;
		try
		{
			if (r instanceof net.runelite.api.DynamicObject)
			{
				StandIns.StandInDynamicObject d = new StandIns.StandInDynamicObject();
				net.runelite.api.DynamicObject src = (net.runelite.api.DynamicObject) r;
				d.height = r.getModelHeight();
				d.mode = r.getRenderMode();
				d.animFrame = src.getAnimFrame();
				d.animCycle = src.getAnimCycle();
				d.zbuf = src.getModelZbuf();
				out = d;
			}
			else
			{
				StandIns.StandInRenderable s = new StandIns.StandInRenderable();
				s.height = r.getModelHeight();
				s.mode = r.getRenderMode();
				s.model = r.getModel();
				out = s;
			}
		}
		catch (RuntimeException e)
		{
			out = r; // cannot copy this one: keep the live object
		}
		ctx.done.put(r, out);
		return out;
	}
}
