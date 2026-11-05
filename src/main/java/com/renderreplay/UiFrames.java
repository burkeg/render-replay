package com.renderreplay;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Delta codec for the client's 2D layer (interface + plugin overlays), one snapshot per frame.
 * A frame is stored as the pixels that changed since the previous frame, deflated: {len, n, (index, argb)*n}.
 */
final class UiFrames
{
	private UiFrames()
	{
	}

	/** Encodes cur relative to prev (prev == null or a different length means "against all zeros"). */
	static byte[] encode(int[] prev, int[] cur)
	{
		boolean same = prev != null && prev.length == cur.length;
		int n = 0;
		for (int i = 0; i < cur.length; i++)
		{
			if (cur[i] != (same ? prev[i] : 0))
			{
				n++;
			}
		}
		ByteBuffer b = ByteBuffer.allocate(8 + n * 8);
		b.putInt(cur.length).putInt(n);
		for (int i = 0; i < cur.length; i++)
		{
			if (cur[i] != (same ? prev[i] : 0))
			{
				b.putInt(i).putInt(cur[i]);
			}
		}
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
		ByteBuffer b = ByteBuffer.wrap(inflate(enc, -1));
		int len = b.getInt(), n = b.getInt();
		if (len != target.length)
		{
			throw new IllegalArgumentException("buffer length " + target.length + " != frame length " + len);
		}
		for (int k = 0; k < n; k++)
		{
			target[b.getInt()] = b.getInt();
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
			java.util.Arrays.fill(target, 0);
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
