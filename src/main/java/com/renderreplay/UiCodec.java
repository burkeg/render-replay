package com.renderreplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec for the 2D layer (interface and overlays), frame by frame, with an encoder and a decoder that keep the same
 * state. A frame is predicted from the one before; the minimap is predicted by turning it by the recorded change in
 * camera yaw (plus a small shift); sprites drawn before (labels, XP drops, hitsplats) are stored as a reference and a
 * position; what is left is stored as runs of changed pixels, packed QOI-style and deflated.
 * Every key-th frame is a checkpoint, stored as changes since the previous checkpoint, and every anchor-th checkpoint
 * stands alone, so playback can start at any anchor. Options make it lossy: fewer colour bits, small changes ignored,
 * only every n-th frame stored (the minimap still turns in between), half resolution.
 */
final class UiCodec
{
	private static final int MAX_GAP = 2, MAX_SPRITES = 4096;

	static final class Opts
	{
		int every = 1, key = 60, anchorEvery = 180, quant = 8, thresh = 0, mmThresh = 0, mmEvery = 0;
		boolean ckDelta = true, mc = true, sprites = true, half, mmFree;
	}

	private UiCodec()
	{
	}

	/** Whether frame f is a checkpoint, and whether that checkpoint stands alone. */
	static boolean isKey(Opts o, int f)
	{
		return f % o.key == 0;
	}

	static boolean isAnchor(Opts o, int f)
	{
		return f % o.key == 0 && (f / o.key) % o.anchorEvery == 0;
	}

	// ---- shared state and steps ----

	private abstract static class State
	{
		final Opts o;
		int w, h, n;              // working size (half of the layer at half resolution)
		int fullW, fullH;
		int[] recon, cur, pred, painted, ck, mmBase;
		int lastF = -1, ckF = -1, mmBaseF = -1;
		float lastYaw, ckYaw, mmBaseYaw;
		int mmSign = 1;
		int[] mm;                 // cx, cy, r in working pixels
		int[] pts;                // minimap pixel indexes
		int[] ptsSearch;          // every third of them: enough to choose the minimap's turn and shift, at a third of the cost
		byte[] inMm;
		byte[] seen;
		int[] stack;
		final List<int[]> sprites = new ArrayList<>(); // id -> {w, h, data...}
		final Map<String, Integer> spriteIds = new HashMap<>();

		State(Opts o)
		{
			this.o = o;
		}

		void size(int fullN, int fullWidth)
		{
			fullW = fullWidth;
			fullH = fullN / fullWidth;
			w = o.half ? fullW >> 1 : fullW;
			h = o.half ? fullH >> 1 : fullH;
			n = w * h;
			recon = cur = pred = painted = ck = mmBase = null;
			mm = null;
			lastF = ckF = mmBaseF = -1;
		}

		void setMinimap(int[] m)
		{
			if (m == null)
			{
				mm = null;
				pts = null;
				ptsSearch = null;
				inMm = null;
				return;
			}
			mm = m;
			List<Integer> a = new ArrayList<>();
			for (int y = Math.max(0, m[1] - m[2]); y <= Math.min(h - 1, m[1] + m[2]); y++)
			{
				for (int x = Math.max(0, m[0] - m[2]); x <= Math.min(w - 1, m[0] + m[2]); x++)
				{
					if ((x - m[0]) * (x - m[0]) + (y - m[1]) * (y - m[1]) <= (m[2] - 1) * (m[2] - 1))
					{
						a.add(y * w + x);
					}
				}
			}
			pts = new int[a.size()];
			ptsSearch = new int[(a.size() + 2) / 3];
			for (int i = 0; i < ptsSearch.length; i++)
			{
				ptsSearch[i] = a.get(i * 3);
			}
			inMm = new byte[n];
			for (int i = 0; i < pts.length; i++)
			{
				pts[i] = a.get(i);
				inMm[pts[i]] = 1;
			}
		}

		int[] buf(int[] b)
		{
			return b != null && b.length == n ? b : new int[n];
		}
	}

	private static void predictMinimap(int[] src, int w, int[] mm, double ang, int sx, int sy, int[] out, int[] pts)
	{
		double c = Math.cos(ang), s = Math.sin(ang);
		int r2 = (mm[2] - 1) * (mm[2] - 1);
		for (int k = 0; k < pts.length; k++)
		{
			int i = pts[k], x = i % w - mm[0], y = i / w - mm[1];
			int u = (int) Math.round(mm[0] + x * c - y * s + sx), v = (int) Math.round(mm[1] + x * s + y * c + sy);
			int du = u - mm[0], dv = v - mm[1];
			out[i] = du * du + dv * dv <= r2 ? src[v * w + u] : src[i];
		}
	}

	private static int misses(int[] cand, int[] cur, int[] pts)
	{
		int m = 0;
		for (int i : pts)
		{
			if (cand[i] != cur[i])
			{
				m++;
			}
		}
		return m;
	}

	/** Runs of cur differing from base (base null: from zero), {start, count} pairs. */
	private static int[] runs(int[] base, int[] cur, int n)
	{
		int[] out = new int[64];
		int k = 0;
		for (int i = 0; i < n; )
		{
			if (cur[i] == (base == null ? 0 : base[i]))
			{
				i++;
				continue;
			}
			int end = i + 1;
			for (int j = end; j < n && j - end <= MAX_GAP; j++)
			{
				if (cur[j] != (base == null ? 0 : base[j]))
				{
					end = j + 1;
				}
			}
			if (k + 2 > out.length)
			{
				out = Arrays.copyOf(out, out.length * 2);
			}
			out[k++] = i;
			out[k++] = end - i;
			i = end;
		}
		return Arrays.copyOf(out, k);
	}

	/** Objects over transparent pixels that changed: connected non-transparent regions outside the minimap. */
	private static List<int[]> changedObjects(State s, int[] cur, int[] ref)
	{
		List<int[]> out = new ArrayList<>();
		if (s.seen == null || s.seen.length != s.n)
		{
			s.seen = new byte[s.n];
			s.stack = new int[s.n];
		}
		byte[] seen = s.seen;
		Arrays.fill(seen, (byte) 0);
		int w = s.w, n = s.n;
		List<Integer> pix = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			if (seen[i] != 0 || (s.inMm != null && s.inMm[i] != 0) || cur[i] == 0 || cur[i] == ref[i])
			{
				continue;
			}
			int sp = 0, cnt = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
			boolean big = false;
			pix.clear();
			s.stack[sp++] = i;
			seen[i] = 1;
			while (sp > 0)
			{
				int j = s.stack[--sp];
				pix.add(j);
				cnt++;
				int x = j % w, y = j / w;
				x0 = Math.min(x0, x);
				x1 = Math.max(x1, x);
				y0 = Math.min(y0, y);
				y1 = Math.max(y1, y);
				if (cnt > 4096)
				{
					big = true;
				}
				int[] nb = {x > 0 ? j - 1 : -1, x < w - 1 ? j + 1 : -1, j - w, j + w};
				for (int k : nb)
				{
					if (k >= 0 && k < n && seen[k] == 0 && (s.inMm == null || s.inMm[k] == 0) && cur[k] != 0)
					{
						seen[k] = 1;
						if (!big)
						{
							s.stack[sp++] = k;
						}
					}
				}
			}
			if (big)
			{
				continue;
			}
			int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
			int[] ob = new int[4 + bw * bh];
			ob[0] = x0;
			ob[1] = y0;
			ob[2] = bw;
			ob[3] = bh;
			for (int j : pix)
			{
				ob[4 + (j / w - y0) * bw + (j % w - x0)] = cur[j];
			}
			out.add(ob);
		}
		return out;
	}

	private static String spriteKey(int[] ob)
	{
		return ob[2] + "x" + ob[3] + ":" + Arrays.hashCode(Arrays.copyOfRange(ob, 4, ob.length)) + ":" + ob.length;
	}

	private static void paint(int[] dst, int w, int[] sprite, int x, int y)
	{
		int bw = sprite[0], bh = sprite[1];
		for (int yy = 0; yy < bh; yy++)
		{
			for (int xx = 0; xx < bw; xx++)
			{
				int v = sprite[2 + yy * bw + xx];
				if (v != 0)
				{
					dst[(y + yy) * w + x + xx] = v;
				}
			}
		}
	}

	/** After a frame is known: sprites seen for the first time join the cache (same order on both sides). */
	private static void learnSprites(State s, int[] cur, int[] ref)
	{
		for (int[] ob : changedObjects(s, cur, ref))
		{
			String k = spriteKey(ob);
			if (!s.spriteIds.containsKey(k) && s.sprites.size() < MAX_SPRITES)
			{
				int[] sp = new int[2 + ob.length - 4];
				sp[0] = ob[2];
				sp[1] = ob[3];
				System.arraycopy(ob, 4, sp, 2, ob.length - 4);
				s.spriteIds.put(k, s.sprites.size());
				s.sprites.add(sp);
			}
		}
	}

	/** End of a frame on either side: what playback shows becomes the reference, checkpoints and minimap kept. */
	private static void finish(State s, int f, float yaw, boolean key, boolean store, int[] cur)
	{
		int[] old = s.recon;
		s.recon = cur;
		s.cur = old != null && old.length == s.n && old != s.ck && old != s.mmBase ? old : null;
		s.lastF = f;
		s.lastYaw = yaw;
		if (key)
		{
			s.ck = s.ck != null && s.ck.length == s.n ? s.ck : new int[s.n];
			System.arraycopy(cur, 0, s.ck, 0, s.n);
			s.ckF = f;
			s.ckYaw = yaw;
		}
		if (store && s.mm != null)
		{
			s.mmBase = s.mmBase != null && s.mmBase.length == s.n ? s.mmBase : new int[s.n];
			for (int i : s.pts)
			{
				s.mmBase[i] = cur[i];
			}
			s.mmBaseF = f;
			s.mmBaseYaw = yaw;
		}
	}

	private static int[] down(int[] full, int fw, int w, int h, int[] out)
	{
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int i = 2 * y * fw + 2 * x;
				int[] p = {full[i], full[i + 1], full[i + fw], full[i + fw + 1]};
				int a = 0, r = 0, g = 0, b = 0;
				for (int v : p)
				{
					a += v >>> 24;
					r += v >>> 16 & 255;
					g += v >>> 8 & 255;
					b += v & 255;
				}
				out[y * w + x] = ((a + 2) >> 2) << 24 | ((r + 2) >> 2) << 16 | ((g + 2) >> 2) << 8 | ((b + 2) >> 2);
			}
		}
		return out;
	}

	private static void up(int[] small, int w, int h, int[] full, int fw, int fh)
	{
		for (int y = 0; y < fh; y++)
		{
			int sy = Math.min(h - 1, y >> 1);
			for (int x = 0; x < fw; x++)
			{
				full[y * fw + x] = small[sy * w + Math.min(w - 1, x >> 1)];
			}
		}
	}

	private static int[] scaleMm(Opts o, int[] mm)
	{
		return mm == null ? null : o.half ? new int[]{mm[0] >> 1, mm[1] >> 1, mm[2] >> 1} : mm;
	}

	// ---- encoder ----

	static final class Encoder extends State
	{
		private int[] lastMmSent;
		private int[] small, cand;
		/** After encode: whether that frame was a checkpoint, and whether it stands alone (no reference). */
		boolean wasKey, wasStandalone;

		Encoder(Opts o)
		{
			super(o);
		}

		/** What playback shows after the last frame encoded, full size. */
		int[] shown()
		{
			if (!o.half)
			{
				return recon;
			}
			int[] out = new int[fullW * fullH];
			up(recon, w, h, out, fullW, fullH);
			return out;
		}

		/**
		 * Codes one frame of the layer (full size, ARGB) as frame number f of the recording, with the camera yaw and
		 * the minimap {cx, cy, r} in layer pixels (or null). Returns the frame's bytes (empty when nothing is stored).
		 */
		byte[] encode(int[] full, int fullWidth, int f, float yaw, int[] mmFull)
		{
			// the client's pixel array can be longer than width x height (by one pixel): only whole rows are the layer
			int fullN = full.length - full.length % fullWidth;
			if (recon == null || fullW != fullWidth || fullW * fullH != fullN)
			{
				size(fullN, fullWidth);
				lastMmSent = null;
			}
			int[] src = full;
			if (o.half)
			{
				small = buf(small);
				src = down(full, fullW, w, h, small);
			}
			int[] mmW = scaleMm(o, mmFull);
			boolean key = isKey(o, f) || recon == null;
			boolean anchor = isAnchor(o, f) || recon == null;
			boolean store = key || f % o.every == 0;
			if (key)
			{
				// a checkpoint starts afresh: sprites, the turn direction and the minimap's place are sent again
				sprites.clear();
				spriteIds.clear();
				mmSign = 1;
			}
			boolean mmChanged = !Arrays.equals(mmW, lastMmSent) || key;
			if (mmChanged)
			{
				setMinimap(mmW);
			}
			int[] ref = key ? (o.ckDelta && !anchor && ck != null ? ck : null) : recon;
			int refF = key ? ckF : lastF;
			float refYaw = key ? ckYaw : lastYaw;
			pred = buf(pred);
			if (ref != null)
			{
				System.arraycopy(ref, 0, pred, 0, n);
			}
			else
			{
				Arrays.fill(pred, 0);
			}
			Bytes.Out out = new Bytes.Out(256);
			// 1 stored, 2 minimap place follows, 4 minimap motion follows, 8 no minimap, 16 stands alone, 32 checkpoint
			int flags = (store ? 1 : 0) | (mmChanged ? (mm != null ? 2 : 8) : 0) | (ref == null ? 16 : 0) | (key ? 32 : 0);
			wasKey = key;
			wasStandalone = ref == null;
			int motionMode = -1, sx = 0, sy = 0;
			if (o.mc && mm != null && ref != null)
			{
				boolean useBase = !key && mmBase != null;
				int[] base = useBase ? mmBase : ref;
				float dyaw = yaw - (useBase ? mmBaseYaw : refYaw);
				if (o.mmFree && !store)
				{
					predictMinimap(base, w, mm, mmSign * (double) dyaw, 0, 0, pred, pts);
				}
				else
				{
					// the turn direction and shift that the fewest minimap pixels disagree with
					int bestMode = 2, bestMiss = Integer.MAX_VALUE;
					cand = buf(cand);
					int[] modes = Math.abs(dyaw) > 1e-6 ? new int[]{0, 1, 2} : new int[]{2};
					for (int md : modes)
					{
						predictMinimap(base, w, mm, angle(md, dyaw), 0, 0, cand, ptsSearch);
						int m = misses(cand, src, ptsSearch);
						if (m < bestMiss)
						{
							bestMiss = m;
							bestMode = md;
						}
					}
					double ang = angle(bestMode, dyaw);
					for (int r = 1; r <= 4 && bestMiss > 0; r++)
					{
						boolean improved = false;
						for (int dy = -r; dy <= r; dy++)
						{
							for (int dx = -r; dx <= r; dx++)
							{
								if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
								{
									continue;
								}
								predictMinimap(base, w, mm, ang, dx, dy, cand, ptsSearch);
								int m = misses(cand, src, ptsSearch);
								if (m < bestMiss)
								{
									bestMiss = m;
									sx = dx;
									sy = dy;
									improved = true;
								}
							}
						}
						if (!improved && r >= 2)
						{
							break;
						}
					}
					motionMode = bestMode;
					predictMinimap(base, w, mm, ang, sx, sy, pred, pts);
					if (bestMode != 2 && Math.abs(dyaw) > 1e-6)
					{
						mmSign = bestMode == 0 ? 1 : -1;
					}
					flags |= 4;
				}
			}
			// what this frame stores: the source, except where options say the prediction is close enough
			cur = buf(cur);
			int keep = o.quant >= 8 ? 0xFFFFFFFF : 0xFF000000 | ((0xFF << (8 - o.quant)) & 0xFF) * 0x010101;
			boolean mmSkip = !key && o.mmEvery > 1 && f % o.mmEvery != 0;
			for (int i = 0; i < n; i++)
			{
				int v = src[i];
				if (o.quant < 8)
				{
					int m = v & keep;
					v = m | ((m & 0x00FFFFFF) >>> o.quant & ~keep & 0x00FFFFFF);
				}
				int p = pred[i];
				if (ref != null && v != p)
				{
					boolean inm = inMm != null && inMm[i] != 0;
					if (!store || (mmSkip && inm) || (o.mmThresh > 0 && inm && close(v, p, o.mmThresh)) || (o.thresh > 0 && !inm && close(v, p, o.thresh)))
					{
						v = p;
					}
				}
				cur[i] = v;
			}
			if (!store && (flags & (2 | 4 | 8)) == 0)
			{
				finish(this, f, yaw, key, false, cur);
				return new byte[0];
			}
			out.u8(flags);
			if ((flags & 2) != 0)
			{
				out.varint(mm[0]);
				out.varint(mm[1]);
				out.varint(mm[2]);
			}
			if ((flags & (2 | 8)) != 0)
			{
				lastMmSent = mmW;
			}
			if ((flags & 4) != 0)
			{
				out.u8(motionMode);
				out.zz(sx);
				out.zz(sy);
			}
			if (store)
			{
				int[] base = ref == null ? null : pred;
				if (o.sprites && ref != null)
				{
					painted = buf(painted);
					System.arraycopy(pred, 0, painted, 0, n);
					Bytes.Out sw = new Bytes.Out(32);
					int hits = 0;
					for (int[] ob : changedObjects(this, cur, pred))
					{
						Integer id = spriteIds.get(spriteKey(ob));
						if (id != null)
						{
							hits++;
							sw.varint(id);
							sw.varint(ob[0]);
							sw.varint(ob[1]);
							paint(painted, w, sprites.get(id), ob[0], ob[1]);
						}
					}
					out.varint(hits);
					out.bytes(sw.b, 0, sw.n);
					base = painted;
				}
				int[] rs = runs(base, cur, n);
				out.varint(rs.length / 2);
				int last = 0, px = 0;
				for (int k = 0; k < rs.length; k += 2)
				{
					out.varint(rs[k] - last);
					out.varint(rs[k + 1]);
					last = rs[k] + rs[k + 1];
					px += rs[k + 1];
				}
				int[] all = new int[px];
				int q = 0;
				for (int k = 0; k < rs.length; k += 2)
				{
					System.arraycopy(cur, rs[k], all, q, rs[k + 1]);
					q += rs[k + 1];
				}
				out.bytes(Qoi.encode(all, px));
			}
			if (o.sprites && store && ref != null)
			{
				learnSprites(this, cur, pred);
			}
			else if (o.sprites && store)
			{
				learnSprites(this, cur, new int[n]);
			}
			finish(this, f, yaw, key, store, cur);
			byte[] payload = out.toArray();
			if (payload.length < 24)
			{
				byte[] r = new byte[payload.length + 1];
				r[0] = 1;
				System.arraycopy(payload, 0, r, 1, payload.length);
				return r;
			}
			byte[] z = ModelStore.deflate(payload);
			byte[] r = new byte[z.length + 1];
			r[0] = 2;
			System.arraycopy(z, 0, r, 1, z.length);
			return r;
		}
	}

	private static double angle(int mode, float dyaw)
	{
		return mode == 0 ? dyaw : mode == 1 ? -dyaw : 0;
	}

	private static boolean close(int a, int b, int t)
	{
		return (a >>> 24) == (b >>> 24) && Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)) <= t
			&& Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)) <= t && Math.abs((a & 255) - (b & 255)) <= t;
	}

	// ---- decoder ----

	static final class Decoder extends State
	{
		private int[] full;

		Decoder(Opts o)
		{
			super(o);
		}

		/** Forgets everything; the next frame decoded must be an anchor. */
		void reset()
		{
			recon = null;
			sprites.clear();
			spriteIds.clear();
		}

		/**
		 * Decodes frame f (frames must come in order from an anchor). fullN and fullWidth are the layer's size.
		 * Returns the full-size layer playback shows (reused between calls).
		 */
		int[] decode(byte[] rec, int f, float yaw, int fullN, int fullWidth)
		{
			fullN -= fullN % fullWidth;
			if (recon == null || fullW != fullWidth || fullW * fullH != fullN)
			{
				size(fullN, fullWidth);
			}
			Bytes.In r = null;
			int flags = 0;
			if (rec.length > 0)
			{
				byte[] payload = rec[0] == 2 ? ModelStore.inflate(Arrays.copyOfRange(rec, 1, rec.length)) : Arrays.copyOfRange(rec, 1, rec.length);
				r = new Bytes.In(payload);
				flags = r.u8();
			}
			else if (recon == null)
			{
				return null; // nothing to show yet
			}
			boolean key = (flags & 32) != 0, standalone = (flags & 16) != 0;
			if (key)
			{
				sprites.clear();
				spriteIds.clear();
				mmSign = 1;
			}
			int[] ref = standalone ? null : key ? ck : recon;
			if (ref == null && !standalone)
			{
				throw new IllegalStateException("2D layer frame " + f + " needs a reference");
			}
			float refYaw = key ? ckYaw : lastYaw;
			boolean store = (flags & 1) != 0;
			pred = buf(pred);
			if (ref != null)
			{
				System.arraycopy(ref, 0, pred, 0, n);
			}
			else
			{
				Arrays.fill(pred, 0);
			}
			if (r != null)
			{
				if ((flags & 2) != 0)
				{
					setMinimap(new int[]{r.varint32(), r.varint32(), r.varint32()});
				}
				else if ((flags & 8) != 0)
				{
					setMinimap(null);
				}
			}
			if (o.mc && mm != null && ref != null)
			{
				boolean useBase = !key && mmBase != null;
				int[] base = useBase ? mmBase : ref;
				float dyaw = yaw - (useBase ? mmBaseYaw : refYaw);
				if ((flags & 4) != 0)
				{
					int mode = r.u8();
					int sx = (int) r.zz(), sy = (int) r.zz();
					predictMinimap(base, w, mm, angle(mode, dyaw), sx, sy, pred, pts);
					if (mode != 2 && Math.abs(dyaw) > 1e-6)
					{
						mmSign = mode == 0 ? 1 : -1;
					}
				}
				else if (o.mmFree && !store)
				{
					predictMinimap(base, w, mm, mmSign * (double) dyaw, 0, 0, pred, pts);
				}
			}
			cur = buf(cur);
			if (standalone && !store)
			{
				throw new IllegalStateException("bad 2D layer frame " + f);
			}
			System.arraycopy(pred, 0, cur, 0, n);
			if (store)
			{
				int[] base = ref == null ? null : pred;
				if (o.sprites && ref != null)
				{
					painted = buf(painted);
					System.arraycopy(pred, 0, painted, 0, n);
					int hits = r.varint32();
					for (int k = 0; k < hits; k++)
					{
						int id = r.varint32(), x = r.varint32(), y = r.varint32();
						paint(painted, w, sprites.get(id), x, y);
					}
					base = painted;
				}
				if (base == null)
				{
					Arrays.fill(cur, 0);
				}
				else
				{
					System.arraycopy(base, 0, cur, 0, n);
				}
				int runs = r.varint32();
				int[] rs = new int[runs * 2];
				int last = 0, px = 0;
				for (int k = 0; k < runs; k++)
				{
					rs[2 * k] = last + r.varint32();
					rs[2 * k + 1] = r.varint32();
					last = rs[2 * k] + rs[2 * k + 1];
					px += rs[2 * k + 1];
				}
				int[] all = Qoi.decode(r.b, r.p, px);
				int q = 0;
				for (int k = 0; k < rs.length; k += 2)
				{
					if (rs[k] < 0 || rs[k] + rs[k + 1] > n)
					{
						throw new IllegalArgumentException("bad run");
					}
					System.arraycopy(all, q, cur, rs[k], rs[k + 1]);
					q += rs[k + 1];
				}
				if (o.sprites)
				{
					learnSprites(this, cur, ref != null ? pred : new int[n]);
				}
			}
			finish(this, f, yaw, key, store, cur);
			if (!o.half)
			{
				return recon;
			}
			full = full != null && full.length == fullN ? full : new int[fullN];
			up(recon, w, h, full, fullW, fullH);
			return full;
		}
	}

	// ---- QOI-style pixel packing ----

	static final class Qoi
	{
		private Qoi()
		{
		}

		private static int hash(int p)
		{
			return ((p >>> 16 & 255) * 3 + (p >>> 8 & 255) * 5 + (p & 255) * 7 + (p >>> 24) * 11) & 63;
		}

		static byte[] encode(int[] px, int n)
		{
			Bytes.Out out = new Bytes.Out(n * 2 + 8);
			int prev = 0xFF000000, run = 0;
			int[] idx = new int[64];
			for (int i = 0; i < n; i++)
			{
				int p = px[i];
				if (p == prev)
				{
					run++;
					if (run == 62 || i == n - 1)
					{
						out.u8(0xC0 | (run - 1));
						run = 0;
					}
					continue;
				}
				if (run > 0)
				{
					out.u8(0xC0 | (run - 1));
					run = 0;
				}
				int h = hash(p);
				if (idx[h] == p)
				{
					out.u8(h);
				}
				else
				{
					idx[h] = p;
					if ((p >>> 24) == (prev >>> 24))
					{
						int dr = (byte) ((p >>> 16 & 255) - (prev >>> 16 & 255)), dg = (byte) ((p >>> 8 & 255) - (prev >>> 8 & 255)), db = (byte) ((p & 255) - (prev & 255));
						int drg = dr - dg, dbg = db - dg;
						if (dr >= -2 && dr <= 1 && dg >= -2 && dg <= 1 && db >= -2 && db <= 1)
						{
							out.u8(0x40 | (dr + 2) << 4 | (dg + 2) << 2 | (db + 2));
						}
						else if (dg >= -32 && dg <= 31 && drg >= -8 && drg <= 7 && dbg >= -8 && dbg <= 7)
						{
							out.u8(0x80 | (dg + 32));
							out.u8((drg + 8) << 4 | (dbg + 8));
						}
						else
						{
							out.u8(0xFE);
							out.u8(p >>> 16 & 255);
							out.u8(p >>> 8 & 255);
							out.u8(p & 255);
						}
					}
					else
					{
						out.u8(0xFF);
						out.u8(p >>> 16 & 255);
						out.u8(p >>> 8 & 255);
						out.u8(p & 255);
						out.u8(p >>> 24);
					}
				}
				prev = p;
			}
			if (run > 0)
			{
				out.u8(0xC0 | (run - 1));
			}
			return out.toArray();
		}

		static int[] decode(byte[] b, int off, int n)
		{
			int[] px = new int[n], idx = new int[64];
			int p = off, prev = 0xFF000000, i = 0;
			while (i < n)
			{
				int op = b[p++] & 0xFF, v;
				if (op == 0xFE)
				{
					v = (prev & 0xFF000000) | (b[p] & 255) << 16 | (b[p + 1] & 255) << 8 | (b[p + 2] & 255);
					p += 3;
				}
				else if (op == 0xFF)
				{
					v = (b[p + 3] & 255) << 24 | (b[p] & 255) << 16 | (b[p + 1] & 255) << 8 | (b[p + 2] & 255);
					p += 4;
				}
				else if ((op & 0xC0) == 0x00)
				{
					v = idx[op];
					prev = v;
					px[i++] = v;
					continue;
				}
				else if ((op & 0xC0) == 0x40)
				{
					int r = ((prev >>> 16) + ((op >> 4 & 3) - 2)) & 255, g = ((prev >>> 8) + ((op >> 2 & 3) - 2)) & 255, bl = (prev + ((op & 3) - 2)) & 255;
					v = (prev & 0xFF000000) | r << 16 | g << 8 | bl;
				}
				else if ((op & 0xC0) == 0x80)
				{
					int dg = (op & 63) - 32, x = b[p++] & 255, dr = dg + (x >> 4) - 8, db = dg + (x & 15) - 8;
					v = (prev & 0xFF000000) | (((prev >>> 16) + dr) & 255) << 16 | (((prev >>> 8) + dg) & 255) << 8 | ((prev + db) & 255);
				}
				else
				{
					int run = (op & 63) + 1;
					for (int k = 0; k < run && i < n; k++)
					{
						px[i++] = prev;
					}
					continue;
				}
				idx[hash(v)] = v;
				prev = v;
				px[i++] = v;
			}
			return px;
		}
	}
}
