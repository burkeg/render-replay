package com.renderreplay;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable byte buffer and reader with the variable-length integers the codecs use. */
final class Bytes
{
	private Bytes()
	{
	}

	static final class Out
	{
		byte[] b;
		int n;

		Out(int size)
		{
			b = new byte[Math.max(16, size)];
		}

		private void need(int k)
		{
			if (n + k > b.length)
			{
				b = Arrays.copyOf(b, Math.max(b.length * 2, n + k));
			}
		}

		void u8(int v)
		{
			need(1);
			b[n++] = (byte) v;
		}

		/** Unsigned variable-length integer. */
		void varint(long v)
		{
			need(10);
			while ((v & ~0x7FL) != 0)
			{
				b[n++] = (byte) ((v & 0x7F) | 0x80);
				v >>>= 7;
			}
			b[n++] = (byte) v;
		}

		/** Signed, zigzag-coded. */
		void zz(long v)
		{
			varint((v << 1) ^ (v >> 63));
		}

		void bytes(byte[] a, int off, int len)
		{
			need(len);
			System.arraycopy(a, off, b, n, len);
			n += len;
		}

		void bytes(byte[] a)
		{
			bytes(a, 0, a.length);
		}

		void str(String s)
		{
			byte[] u = s.getBytes(StandardCharsets.UTF_8);
			varint(u.length);
			bytes(u);
		}

		byte[] toArray()
		{
			return Arrays.copyOf(b, n);
		}
	}

	static final class In
	{
		final byte[] b;
		int p;
		final int end;

		In(byte[] b)
		{
			this(b, 0, b.length);
		}

		In(byte[] b, int off, int len)
		{
			this.b = b;
			this.p = off;
			this.end = off + len;
		}

		int u8()
		{
			if (p >= end)
			{
				throw new IllegalArgumentException("truncated data");
			}
			return b[p++] & 0xFF;
		}

		long varint()
		{
			long v = 0;
			for (int shift = 0; ; shift += 7)
			{
				int x = u8();
				v |= (long) (x & 0x7F) << shift;
				if ((x & 0x80) == 0)
				{
					return v;
				}
				if (shift > 63)
				{
					throw new IllegalArgumentException("bad varint");
				}
			}
		}

		int varint32()
		{
			return (int) varint();
		}

		long zz()
		{
			long v = varint();
			return (v >>> 1) ^ -(v & 1);
		}

		String str()
		{
			int k = varint32();
			if (k < 0 || p + k > end)
			{
				throw new IllegalArgumentException("bad string");
			}
			String s = new String(b, p, k, StandardCharsets.UTF_8);
			p += k;
			return s;
		}

		boolean done()
		{
			return p >= end;
		}
	}
}
