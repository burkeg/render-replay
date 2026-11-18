package com.renderreplay;

/**
 * The recording qualities the panel's slider offers: the Pareto-optimal pairings of a world option and a 2D-layer
 * option, from exact to smallest, with what each measured on a sample recording (bytes per frame in the long run, and
 * PSNR against exact playback, 0 meaning exact).
 */
final class Presets
{
	static final class Preset
	{
		final String name;
		final double vq, poseTol;          // vertex rounding (1/vq units, 0 exact), pose reuse within poseTol units
		final int every;                   // world calls kept every n-th frame
		final boolean interp, freeze;      // smooth between kept frames; keep each object's first pose
		final int uiEvery, uiKey, quant, thresh, mmThresh, mmEvery;
		final boolean half, mmFree;
		final int labBytesPerFrame;
		final double labPsnr;

		Preset(String name, double vq, double poseTol, int every, boolean interp, boolean freeze, int uiEvery, int uiKey, int quant,
			int thresh, int mmThresh, int mmEvery, boolean half, boolean mmFree, int labBytesPerFrame, double labPsnr)
		{
			this.name = name;
			this.vq = vq;
			this.poseTol = poseTol;
			this.every = every;
			this.interp = interp;
			this.freeze = freeze;
			this.uiEvery = uiEvery;
			this.uiKey = uiKey;
			this.quant = quant;
			this.thresh = thresh;
			this.mmThresh = mmThresh;
			this.mmEvery = mmEvery;
			this.half = half;
			this.mmFree = mmFree;
			this.labBytesPerFrame = labBytesPerFrame;
			this.labPsnr = labPsnr;
		}

		UiCodec.Opts uiOpts()
		{
			UiCodec.Opts o = new UiCodec.Opts();
			o.every = uiEvery;
			o.key = uiKey;
			o.anchorEvery = Math.max(1, anchorFrames / uiKey);
			o.quant = quant;
			o.thresh = thresh;
			o.mmThresh = mmThresh;
			o.mmEvery = mmEvery;
			o.half = half;
			o.mmFree = mmFree;
			return o;
		}

		boolean exact()
		{
			return labPsnr == 0;
		}
	}

	/** About how many frames a 2D-layer anchor span covers (anchors are where playback can start and old frames go). */
	static int anchorFrames = 10800;

	static final Preset[] ALL = {
		new Preset("World lossless + 2D layer lossless", 0, 0, 1, false, false, 1, 60, 8, 0, 0, 0, false, false, 1301, 0.0),
		new Preset("Vertices to 1/16 unit + 2D layer lossless", 16, 0, 1, false, false, 1, 60, 8, 0, 0, 0, false, false, 1095, 60.1),
		new Preset("World lossless + Minimap ignores small prediction errors", 0, 0, 1, false, false, 1, 60, 8, 0, 24, 0, false, false, 948, 54.4),
		new Preset("Vertices to 1/16 unit + Minimap ignores small prediction errors", 16, 0, 1, false, false, 1, 60, 8, 0, 24, 0, false, false, 742, 53.4),
		new Preset("Vertices to 1/2 unit + Minimap ignores small prediction errors", 2, 0, 1, false, false, 1, 60, 8, 0, 24, 0, false, false, 696, 50.3),
		new Preset("World at half rate, smoothed + Minimap ignores small prediction errors", 16, 0, 2, true, false, 1, 60, 8, 0, 24, 0, false, false, 687, 43.8),
		new Preset("Vertices to 1/16 unit + Minimap corrected every 15th frame", 16, 0, 1, false, false, 1, 60, 8, 0, 0, 15, false, false, 659, 40.8),
		new Preset("Vertices to 1/16 unit + Minimap every 15th, rest ignores changes up to 8", 16, 0, 1, false, false, 1, 60, 8, 8, 0, 15, false, false, 573, 40.8),
		new Preset("Vertices to 1/2 unit + Minimap every 15th, rest ignores changes up to 8", 2, 0, 1, false, false, 1, 60, 8, 8, 0, 15, false, false, 527, 40.5),
		new Preset("World at half rate, smoothed + Minimap every 15th, rest ignores changes up to 8", 16, 0, 2, true, false, 1, 60, 8, 8, 0, 15, false, false, 518, 39.2),
		new Preset("World at a sixth of the rate, smoothed, 1-unit vertices + Minimap corrected every 15th frame", 1, 0, 6, true, false, 1, 60, 8, 0, 0, 15, false, false, 494, 36.2),
		new Preset("World at a sixth of the rate, smoothed, 1-unit vertices + Minimap every 15th, rest ignores changes up to 8", 1, 0, 6, true, false, 1, 60, 8, 8, 0, 15, false, false, 408, 36.2),
		new Preset("World at a tenth of the rate, smoothed, 2-unit vertices, poses reused within 4 + Minimap every 15th, rest ignores changes up to 8", 0.5, 4, 10, true, false, 1, 60, 8, 8, 0, 15, false, false, 374, 34.8),
		new Preset("No animation: first pose only, moves smoothed from every 10th frame + Minimap every 15th, rest ignores changes up to 8", 0.5, 0, 10, true, true, 1, 60, 8, 8, 0, 15, false, false, 337, 34.4),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + Minimap every 15th, rest ignores changes up to 8", 0.5, 0, 30, true, true, 1, 60, 8, 8, 0, 15, false, false, 323, 32.5),
		new Preset("No animation: first pose only, moves smoothed from every 10th frame + 2D layer every 6th frame, minimap still turning", 0.5, 0, 10, true, true, 6, 60, 8, 0, 0, 0, false, false, 300, 28.7),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + 2D layer every 6th frame, minimap still turning", 0.5, 0, 30, true, true, 6, 60, 8, 0, 0, 0, false, false, 286, 28.0),
		new Preset("World at a tenth of the rate, smoothed, 2-unit vertices, poses reused within 4 + Every 15th frame, 5-bit colours, minimap turning", 0.5, 4, 10, true, false, 15, 60, 5, 0, 0, 0, false, false, 268, 27.0),
		new Preset("No animation: first pose only, moves smoothed from every 10th frame + Every 15th frame, 5-bit colours, minimap turning", 0.5, 0, 10, true, true, 15, 60, 5, 0, 0, 0, false, false, 232, 26.9),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + Every 15th frame, 5-bit colours, minimap turning", 0.5, 0, 30, true, true, 15, 60, 5, 0, 0, 0, false, false, 218, 26.5),
		new Preset("World at a sixth of the rate, smoothed, 1-unit vertices + Every 30th frame, 4-bit colours, minimap turning", 1, 0, 6, true, false, 30, 60, 4, 0, 0, 0, false, false, 216, 25.9),
		new Preset("World at a tenth of the rate, smoothed, 2-unit vertices, poses reused within 4 + Every 30th frame, 4-bit colours, minimap turning", 0.5, 4, 10, true, false, 30, 60, 4, 0, 0, 0, false, false, 182, 25.7),
		new Preset("No animation: first pose only, moves smoothed from every 10th frame + Every 30th frame, 4-bit colours, minimap turning", 0.5, 0, 10, true, true, 30, 60, 4, 0, 0, 0, false, false, 145, 25.7),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + Every 30th frame, 4-bit colours, minimap turning", 0.5, 0, 30, true, true, 30, 60, 4, 0, 0, 0, false, false, 132, 25.4),
		new Preset("World at a tenth of the rate, smoothed, 2-unit vertices, poses reused within 4 + Every 60th frame, 4-bit colours, minimap turns from the camera", 0.5, 4, 10, true, false, 60, 60, 4, 0, 0, 0, false, true, 124, 24.5),
		new Preset("No animation: first pose only, moves smoothed from every 10th frame + Every 60th frame, 4-bit colours, minimap turns from the camera", 0.5, 0, 10, true, true, 60, 60, 4, 0, 0, 0, false, true, 87, 24.4),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + Every 60th frame, 4-bit colours, minimap turns from the camera", 0.5, 0, 30, true, true, 60, 60, 4, 0, 0, 0, false, true, 74, 24.2),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + Half resolution, every 60th frame, 3-bit colours", 0.5, 0, 30, true, true, 60, 60, 3, 0, 0, 0, true, false, 71, 22.0),
		new Preset("No animation: first pose only, moves smoothed from every 10th frame + Half resolution, every 180th frame, 3-bit colours, minimap turns from the camera", 0.5, 0, 10, true, true, 180, 180, 3, 0, 0, 0, true, true, 71, 21.1),
		new Preset("No animation: first pose only, moves smoothed from every 30th frame + Half resolution, every 180th frame, 3-bit colours, minimap turns from the camera", 0.5, 0, 30, true, true, 180, 180, 3, 0, 0, 0, true, true, 57, 21.0)
	};

	private Presets()
	{
	}
}
