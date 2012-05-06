package isabelle.eclipse.core.text;

import isabelle.Standard_System;
import isabelle.Symbol;
import isabelle.eclipse.core.IsabelleCorePlugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

@Deprecated
public class IsabelleEncoding {

	private static final int BUFSIZE = 32768;
	private static final String CHARSET_NAME = Standard_System.charset_name();
	private static final Charset CHARSET = Standard_System.charset();
	
	private static InputStream openIsabelleInputStream(InputStream in, 
			CharsetDecoder decoder) throws IOException {
		
		String sourceText = readAll(new BufferedInputStream(in), decoder);
		String decodedText = Symbol.decode(sourceText);
	    return new ByteArrayInputStream(decodedText.getBytes(CHARSET));
	}
	
	public static InputStream openIsabelleInputStream(InputStream in)
			throws IOException {
		return openIsabelleInputStream(in, CHARSET.newDecoder());
	}
	
	public static InputStream openPermissiveIsabelleInputStream(InputStream in)
			throws IOException {
		
		CharsetDecoder decoder = CHARSET.newDecoder();
		decoder.onMalformedInput(CodingErrorAction.REPLACE);
	    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
		
		return openIsabelleInputStream(in, decoder);
	}
	
	public static OutputStream openIsabelleOutputStream(final OutputStream out) {
		
		if (!IsabelleCorePlugin.getIsabelle().isInit()) {
			// no Isabelle running, cannot convert to isabelle encoding - TODO do not save?
			return out;
		}
		
		return new FlushByteArrayOutputStream(BUFSIZE) {
			
			@Override
			public void flush() throws IOException {
		        String encodedContents = Symbol.encode(toString(CHARSET_NAME));
		        out.write(encodedContents.getBytes(CHARSET));
		        out.flush();
		        super.flush();
			}
			
			@Override
			public void close() throws IOException {
				super.close();
				out.close();
			}
		};
		
	}
	
	public static String readAll(InputStream is, CharsetDecoder decoder) throws IOException {
		return readAll(new InputStreamReader(is, decoder));
	}
	
	public static String readAll(Reader in) throws IOException {
		final char[] buffer = new char[0x10000];
		StringBuilder out = new StringBuilder();
		int read;
		do {
		  read = in.read(buffer, 0, buffer.length);
		  if (read>0) {
		    out.append(buffer, 0, read);
		  }
		} while (read>=0);
		
		return out.toString();
	}
	
	public static class FlushByteArrayOutputStream extends ByteArrayOutputStream {

		private boolean needsFlushing = false;
		
		public FlushByteArrayOutputStream() {
			super();
		}

		public FlushByteArrayOutputStream(int size) {
			super(size);
		}

		@Override
		public synchronized void write(int b) {
			super.write(b);
			needsFlushing = true;
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			super.write(b, off, len);
			needsFlushing = true;
		}

		@Override
		public void flush() throws IOException {
			super.flush();
			needsFlushing = false;
		}

		@Override
		public void close() throws IOException {
			if (needsFlushing) {
				flush();
			}
			
			super.close();
		}
		
	}
	
}