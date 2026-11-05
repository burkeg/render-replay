package com.renderreplay;

import java.util.Set;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Every DrawCallbacks method the recorder handles, as a typed value. A call is stored as (Call, Object[] args) and
 * re-issued with an ordinary, statically typed method call in invoke().
 */
enum Call
{
	DRAW_PROJ("draw", Projection.class, Scene.class, Renderable.class, int.class, int.class, int.class, int.class, long.class),
	DRAW_SCENE_PAINT("drawScenePaint", Scene.class, SceneTilePaint.class, int.class, int.class, int.class),
	DRAW_SCENE_TILE_MODEL("drawSceneTileModel", Scene.class, SceneTileModel.class, int.class, int.class),
	DRAW_FRAME_END("draw", int.class),
	DRAW_SCENE("drawScene", double.class, double.class, double.class, double.class, double.class, int.class),
	POST_DRAW_SCENE("postDrawScene"),
	ANIMATE("animate", Texture.class, int.class),
	LOAD_SCENE("loadScene", Scene.class),
	SWAP_SCENE("swapScene", Scene.class),
	TILE_IN_FRUSTUM("tileInFrustum", Scene.class, float.class, float.class, float.class, float.class, int.class, int.class, int.class, int.class, int.class, int.class),
	ZONE_IN_FRUSTUM("zoneInFrustum", int.class, int.class, int.class, int.class),
	LOAD_SCENE_WV("loadScene", WorldView.class, Scene.class),
	DESPAWN_WORLD_VIEW("despawnWorldView", WorldView.class),
	PRE_SCENE_DRAW_PROJ("preSceneDraw", Scene.class, Projection.class, float.class, float.class, float.class, float.class, float.class, int.class, int.class, int.class, Set.class),
	PRE_SCENE_DRAW("preSceneDraw", Scene.class, float.class, float.class, float.class, float.class, float.class, int.class, int.class, int.class, Set.class),
	POST_SCENE_DRAW("postSceneDraw", Scene.class),
	DRAW_PASS("drawPass", Projection.class, Scene.class, int.class),
	DRAW_ZONE_OPAQUE("drawZoneOpaque", Projection.class, Scene.class, int.class, int.class),
	DRAW_ZONE_ALPHA("drawZoneAlpha", Projection.class, Scene.class, int.class, int.class, int.class),
	DRAW_DYNAMIC("drawDynamic", Projection.class, Scene.class, TileObject.class, Renderable.class, Model.class, int.class, int.class, int.class, int.class),
	DRAW_DYNAMIC_WORKER("drawDynamic", int.class, Projection.class, Scene.class, TileObject.class, Renderable.class, Model.class, int.class, int.class, int.class, int.class),
	DRAW_TEMP("drawTemp", Projection.class, Scene.class, GameObject.class, Model.class, int.class, int.class, int.class, int.class),
	INVALIDATE_ZONE("invalidateZone", Scene.class, int.class, int.class);

	/** The DrawCallbacks method name, as written in the recording files. */
	final String methodName;
	/** Parameter types, used to convert numbers read from a recording file. */
	final Class<?>[] types;

	Call(String methodName, Class<?>... types)
	{
		this.methodName = methodName;
		this.types = types;
	}

	int arity()
	{
		return types.length;
	}

	/** The frame-end draw(int) call: passed through live after the recorded 2D layer is drawn. */
	boolean isFrameEnd()
	{
		return this == DRAW_FRAME_END;
	}

	/** The client's own scene changes: never replayed, handled apart from drawing (DrawRecorder.sceneChange). */
	boolean isSceneChange()
	{
		return this == LOAD_SCENE || this == LOAD_SCENE_WV || this == SWAP_SCENE || this == INVALIDATE_ZONE || this == DESPAWN_WORLD_VIEW;
	}

	private static int i(Object o)
	{
		return ((Number) o).intValue();
	}

	private static float f(Object o)
	{
		return ((Number) o).floatValue();
	}

	private static double d(Object o)
	{
		return ((Number) o).doubleValue();
	}

	/** Issues this call on t. Returns the result for the two boolean methods, else null. */
	@SuppressWarnings("unchecked")
	Object invoke(DrawCallbacks t, Object[] a)
	{
		switch (this)
		{
			case DRAW_PROJ:
				t.draw((Projection) a[0], (Scene) a[1], (Renderable) a[2], i(a[3]), i(a[4]), i(a[5]), i(a[6]), ((Number) a[7]).longValue());
				return null;
			case DRAW_SCENE_PAINT:
				t.drawScenePaint((Scene) a[0], (SceneTilePaint) a[1], i(a[2]), i(a[3]), i(a[4]));
				return null;
			case DRAW_SCENE_TILE_MODEL:
				t.drawSceneTileModel((Scene) a[0], (SceneTileModel) a[1], i(a[2]), i(a[3]));
				return null;
			case DRAW_FRAME_END:
				t.draw(i(a[0]));
				return null;
			case DRAW_SCENE:
				t.drawScene(d(a[0]), d(a[1]), d(a[2]), d(a[3]), d(a[4]), i(a[5]));
				return null;
			case POST_DRAW_SCENE:
				t.postDrawScene();
				return null;
			case ANIMATE:
				t.animate((Texture) a[0], i(a[1]));
				return null;
			case LOAD_SCENE:
				t.loadScene((Scene) a[0]);
				return null;
			case SWAP_SCENE:
				t.swapScene((Scene) a[0]);
				return null;
			case TILE_IN_FRUSTUM:
				return t.tileInFrustum((Scene) a[0], f(a[1]), f(a[2]), f(a[3]), f(a[4]), i(a[5]), i(a[6]), i(a[7]), i(a[8]), i(a[9]), i(a[10]));
			case ZONE_IN_FRUSTUM:
				return t.zoneInFrustum(i(a[0]), i(a[1]), i(a[2]), i(a[3]));
			case LOAD_SCENE_WV:
				t.loadScene((WorldView) a[0], (Scene) a[1]);
				return null;
			case DESPAWN_WORLD_VIEW:
				t.despawnWorldView((WorldView) a[0]);
				return null;
			case PRE_SCENE_DRAW_PROJ:
				t.preSceneDraw((Scene) a[0], (Projection) a[1], f(a[2]), f(a[3]), f(a[4]), f(a[5]), f(a[6]), i(a[7]), i(a[8]), i(a[9]), (Set<Integer>) a[10]);
				return null;
			case PRE_SCENE_DRAW:
				t.preSceneDraw((Scene) a[0], f(a[1]), f(a[2]), f(a[3]), f(a[4]), f(a[5]), i(a[6]), i(a[7]), i(a[8]), (Set<Integer>) a[9]);
				return null;
			case POST_SCENE_DRAW:
				t.postSceneDraw((Scene) a[0]);
				return null;
			case DRAW_PASS:
				t.drawPass((Projection) a[0], (Scene) a[1], i(a[2]));
				return null;
			case DRAW_ZONE_OPAQUE:
				t.drawZoneOpaque((Projection) a[0], (Scene) a[1], i(a[2]), i(a[3]));
				return null;
			case DRAW_ZONE_ALPHA:
				t.drawZoneAlpha((Projection) a[0], (Scene) a[1], i(a[2]), i(a[3]), i(a[4]));
				return null;
			case DRAW_DYNAMIC:
				t.drawDynamic((Projection) a[0], (Scene) a[1], (TileObject) a[2], (Renderable) a[3], (Model) a[4], i(a[5]), i(a[6]), i(a[7]), i(a[8]));
				return null;
			case DRAW_DYNAMIC_WORKER:
				t.drawDynamic(i(a[0]), (Projection) a[1], (Scene) a[2], (TileObject) a[3], (Renderable) a[4], (Model) a[5], i(a[6]), i(a[7]), i(a[8]), i(a[9]));
				return null;
			case DRAW_TEMP:
				t.drawTemp((Projection) a[0], (Scene) a[1], (GameObject) a[2], (Model) a[3], i(a[4]), i(a[5]), i(a[6]), i(a[7]));
				return null;
			case INVALIDATE_ZONE:
				t.invalidateZone((Scene) a[0], i(a[1]), i(a[2]));
				return null;
			default:
				throw new IllegalStateException(this.name());
		}
	}

	/** Finds the call by its name and argument count (recording files store exactly these two). */
	static Call find(String methodName, int arity)
	{
		for (Call c : values())
		{
			if (c.methodName.equals(methodName) && c.arity() == arity)
			{
				return c;
			}
		}
		return null;
	}
}
