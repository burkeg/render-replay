package com.renderreplay;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Delta codec for the client's 2D layer (interface + plugin overlays), one snapshot per frame.
 * A frame is stored as the runs of pixels that changed since the previous frame, deflated:
 * {len, (start, count, argb * count)*}.
 */
final class UiFrames
{
	// A run continues over this many unchanged pixels: storing them costs less than the header of a new run.
	private static final int MAX_GAP = 2;

	private UiFrames()
	{
	}

	/** Encodes cur relative to prev (prev == null or a different length means "against all zeros"). */
	static byte[] encode(int[] prev, int[] cur)
	{
		boolean same = prev != null && prev.length == cur.length;
		int len = cur.length;
		int[] runs = new int[1024];
		int n = 0;
		for (int i = 0; i < len; )
		{
			if (cur[i] == (same ? prev[i] : 0))
			{
				i++;
				continue;
			}
			int end = i + 1; // one past the last changed pixel of this run
			for (int j = end; j < len && j - end <= MAX_GAP; j++)
			{
				if (cur[j] != (same ? prev[j] : 0))
				{
					end = j + 1;
				}
			}
			int count = end - i;
			if (n + 2 + count > runs.length)
			{
				runs = Arrays.copyOf(runs, Math.max(runs.length * 2, n + 2 + count));
			}
			runs[n++] = i;
			runs[n++] = count;
			System.arraycopy(cur, i, runs, n, count);
			n += count;
			i = end;
		}
		ByteBuffer b = ByteBuffer.allocate(4 + n * 4);
		b.putInt(len);
		b.asIntBuffer().put(runs, 0, n);
		Deflater d = new Deflater(Deflater.BEST_SPEED);
		d.setInput(b.array());
		d.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, n));
		byte[] buf = new byte[8192];
		while (!d.finished())
		{
			out.write(buf, 0, d.deflate(buf));
		}
		d.end();
		return out.toByteArray();
	}

	/** Length of the full buffer an encoded frame belongs to. */
	static int length(byte[] enc)
	{
		return ByteBuffer.wrap(inflate(enc, 4)).getInt();
	}

	/** Applies an encoded delta on top of target, which must already hold the previous frame. */
	static void apply(byte[] enc, int[] target)
	{
		IntBuffer b = ByteBuffer.wrap(inflate(enc, -1)).asIntBuffer();
		int len = b.get();
		if (len != target.length)
		{
			throw new IllegalArgumentException("buffer length " + target.length + " != frame length " + len);
		}
		while (b.remaining() >= 2)
		{
			int start = b.get(), count = b.get();
			if (start < 0 || count < 0 || count > len - start || count > b.remaining())
			{
				throw new IllegalArgumentException("bad run " + start + "+" + count);
			}
			b.get(target, start, count);
		}
	}

	/**
	 * Applies one recorded frame to target. A keyframe is encoded against an all-zero screen, so it must start from
	 * a cleared buffer: otherwise pixels that are transparent in the keyframe but non-zero in target (e.g. an overlay
	 * that has since moved) would linger as ghosts. Delta frames are applied on top of the previous frame as-is.
	 */
	static void applyFrame(byte[] enc, int[] target, boolean keyframe)
	{
		if (keyframe)
		{
			Arrays.fill(target, 0);
		}
		apply(enc, target);
	}

	private static byte[] inflate(byte[] enc, int max)
	{
		Inflater in = new Inflater();
		in.setInput(enc);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		try
		{
			while (!in.finished() && (max < 0 || out.size() < max))
			{
				int r = in.inflate(buf);
				if (r == 0 && (in.needsInput() || in.needsDictionary()))
				{
					break;
				}
				out.write(buf, 0, r);
			}
		}
		catch (DataFormatException e)
		{
			throw new IllegalArgumentException(e);
		}
		finally
		{
			in.end();
		}
		return out.toByteArray();
	}
}
