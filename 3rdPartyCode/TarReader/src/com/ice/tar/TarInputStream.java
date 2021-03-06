/*
** Authored by Timothy Gerard Endres
** <mailto:time@gjt.org>  <http://www.trustice.com>
** 
** This work has been placed into the public domain.
** You may use this work in any way and for any purpose you wish.
**
** THIS SOFTWARE IS PROVIDED AS-IS WITHOUT WARRANTY OF ANY KIND,
** NOT EVEN THE IMPLIED WARRANTY OF MERCHANTABILITY. THE AUTHOR
** OF THIS SOFTWARE, ASSUMES _NO_ RESPONSIBILITY FOR ANY
** CONSEQUENCE RESULTING FROM THE USE, MODIFICATION, OR
** REDISTRIBUTION OF THIS SOFTWARE. 
** 
*/

package com.ice.tar;

import java.io.*;

/**
 * The TarInputStream reads a UNIX tar archive as an InputStream.
 * methods are provided to position at each successive entry in
 * the archive, and the read each entry as a normal input stream
 * using read().
 * <p/>
 * A tar archive is a series of entries, each of
 * which represents a file system object. Each entry in
 * the archive consists of a header record. Directory entries
 * consist only of the header record, and are followed by entries
 * for the directory's contents. File entries consist of a
 * header record followed by the number of records needed to
 * contain the file's contents. All entries are written on
 * record boundaries. Records are 512 bytes long.
 * <p/>
 * Kerry Menzel <kmenzel@cfl.rr.com> Contributed the code to support
 * file sizes greater than 2GB (longs versus ints).
 *
 * @author Timothy Gerard Endres, <time@gjt.org>
 * @version $Revision: 1.9 $
 * @see TarBuffer
 * @see TarHeader
 * @see TarEntry
 */

@SuppressWarnings({"NullableProblems", "UnusedDeclaration"})
public class TarInputStream
        extends FilterInputStream {
    private static final long K32 = 32 * 2014;

    protected boolean debug;
    protected boolean hasHitEOF;

    protected long entrySize;
    protected long entryOffset;

    protected byte[] oneBuf = new byte[1];
    protected byte[] readBuf;

    protected TarBuffer buffer;

    protected TarEntry currEntry;

    public TarInputStream( InputStream is ) {
        super( is );

        buffer = new TarBuffer( is );
    }

    /**
     * Sets the debugging flag.
     *
     * @param debugF True to turn on debugging.
     */
    public void setDebug( boolean debugF ) {
        this.debug = debugF;
    }

    /**
     * Sets the debugging flag in this stream's TarBuffer.
     */
    public void setBufferDebug( boolean debug ) {
        buffer.setDebug( debug );
    }

    /**
     * Closes this stream. Calls the TarBuffer's close() method.
     */
    public void close()
            throws IOException {
        buffer.close();
    }

    /**
     * Get the record size being used by this stream's TarBuffer.
     *
     * @return The TarBuffer record size.
     */
    public int getRecordSize() {
        return buffer.getRecordSize();
    }

    /**
     * Get the available data that can be read from the current
     * entry in the archive. This does not indicate how much data
     * is left in the entire archive, only in the current entry.
     * This value is determined from the entry's size header field
     * and the amount of data already read from the current entry.
     *
     * @return The number of available bytes for the current entry.
     */
    public int available()
            throws IOException {
        return (int) (entrySize - entryOffset);
    }

    /**
     * Skip bytes in the input buffer. This skips bytes in the
     * current entry's data, not the entire archive, and will
     * stop at the end of the current entry's data if the number
     * to skip extends beyond that point.
     *
     * @param numToSkip The number of bytes to skip.
     *
     * @return The actual number of bytes skipped.
     */
    public long skip( long numToSkip )
            throws IOException {
        // REVIEW
        // This is horribly inefficient, but it ensures that we
        // properly skip over bytes via the TarBuffer...
        //

        byte[] skipBuf = new byte[8 * 1024];
        long num = numToSkip;
        for (; num > 0; ) {
            int numRead = read( skipBuf, 0,
                                (num > skipBuf.length ? skipBuf.length : (int) num) );

            if ( numRead == -1 ) {
                break;
            }

            num -= numRead;
        }

        return (numToSkip - num);
    }

    /**
     * Since we do not support marking just yet, we return false.
     *
     * @return False.
     */
    public boolean markSupported() {
        return false;
    }

    /**
     * Since we do not support marking just yet, we do nothing.
     *
     * @param markLimit The limit to mark.
     */
    public void mark( int markLimit ) {
    }

    /**
     * Since we do not support marking just yet, we do nothing.
     */
    public void reset() {
    }

    /**
     * Get the number of bytes into the current TarEntry.
     * This method returns the number of bytes that have been read
     * from the current TarEntry's data.
     *
     * @return The current entry offset.
     */

    public long getEntryPosition() {
        return entryOffset;
    }

    /**
     * Get the number of bytes into the stream we are currently at.
     * This method accounts for the blocking stream that tar uses,
     * so it represents the actual position in input stream, as
     * opposed to the place where the tar archive parsing is.
     *
     * @return The current file pointer.
     */

    public long getStreamPosition() {
        return (buffer.getBlockSize() * buffer.getCurrentBlockNum())
               + buffer.getCurrentRecordNum();
    }

    /**
     * Get the next entry in this tar archive. This will skip
     * over any remaining data in the current entry, if there
     * is one, and place the input stream at the header of the
     * next entry, and read the header and instantiate a new
     * TarEntry from the header bytes and return that entry.
     * If there are no more entries in the archive, null will
     * be returned to indicate that the end of the archive has
     * been reached.
     *
     * @return The next TarEntry in the archive, or null.
     */
    public TarEntry getNextEntry()
            throws IOException {
        TarEntry zEntry;
        do {
            if ( null != (zEntry = nextEntry()) ) {
                if ( zEntry.getAction().extended() ) {
                    zEntry = processExtended( zEntry );
                }
            }
        } while ( (zEntry != null) && zEntry.getAction().ignore() );
        return zEntry;
    }

    private TarEntry processExtended( TarEntry pEntry )
            throws IOException {
        if ( pEntry.getTypeFlag() == TarHeader.TypeFlag.GNU_LongName ) {
            String zLongName = readAsStringMax32K( pEntry.getSize() );
            TarEntry zEntry = nextEntry();
            if ( zEntry != null ) {
                zEntry.updateName( zLongName );
            }
            return zEntry;
        }
        System.out.println( "---- Extended ----> " + currEntry.getHeader() );
        return pEntry;
    }

    private String readAsStringMax32K( long pSize )
            throws IOException {
        if ( pSize > K32 ) {
            throw new IOException( "Expected String Size exceeded 32K: " + pSize );
        }
        byte[] zBytes = read( (int) pSize );
        int zLength = zBytes.length;
        if ( (zLength != 0) && (zBytes[zLength - 1] == 0) ) {
            zLength--;
        }
        return (zLength == 0) ? "" : new String( zBytes, 0, zLength, THF.UTF_8 );
    }

    private byte[] read( int pSize )
            throws IOException {
        byte[] buf = new byte[pSize];
        int zRead;
        for ( int zOffset = 0; pSize > 0; zOffset += zRead, pSize -= zRead ) {
            if ( -1 == (zRead = read( buf, zOffset, pSize )) ) {
                throw new IOException( "Unexpected EOF" );
            }
        }
        return buf;
    }

    private TarEntry nextEntry()
            throws IOException {
        if ( hasHitEOF ) {
            return currEntry = null;
        }

        if ( currEntry != null ) {
            long numToSkip = (entrySize - entryOffset);

            if ( debug ) {
                System.err.println(
                        "TarInputStream: SKIP currENTRY '"
                        + currEntry.getName() + "' SZ "
                        + entrySize + " OFF " + entryOffset
                        + "  skipping " + numToSkip + " bytes" );
            }

            if ( numToSkip > 0 ) {
                //noinspection ResultOfMethodCallIgnored
                skip( numToSkip );
            }

            readBuf = null;
        }

        byte[] headerBuf = buffer.readRecord();

        if ( headerBuf == null ) {
            if ( debug ) {
                System.err.println( "READ NULL RECORD" );
            }

            hasHitEOF = true;
        } else if ( buffer.isEOFRecord( headerBuf ) ) {
            if ( debug ) {
                System.err.println( "READ EOF RECORD" );
            }

            hasHitEOF = true;
        }

        if ( hasHitEOF ) {
            return currEntry = null;
        }
        entrySize = 0;
        entryOffset = 0;
        try {
            currEntry = new TarEntry( headerBuf );

            if ( debug ) {
                System.err.println(
                        "TarInputStream: SET CURRENTRY '"
                        + currEntry.getName()
                        + "' size = " + currEntry.getSize() );
            }
            entrySize = currEntry.getSize();
            return currEntry;
        }
        catch ( InvalidHeaderException ex ) {
            currEntry = null;
            throw new InvalidHeaderException(
                    "bad header in block "
                    + buffer.getCurrentBlockNum()
                    + " record "
                    + buffer.getCurrentRecordNum(), ex );
        }
    }

    /**
     * Reads a byte from the current tar archive entry.
     * <p/>
     * This method simply calls read( byte[], int, int ).
     *
     * @return The byte read, or -1 at EOF.
     */
    public int read()
            throws IOException {
        int num = read( oneBuf, 0, 1 );
        if ( num == -1 ) {
            return num;
        } else {
            return (int) oneBuf[0];
        }
    }

    /**
     * Reads bytes from the current tar archive entry.
     * <p/>
     * This method simply calls read( byte[], int, int ).
     *
     * @param buf The buffer into which to place bytes read.
     *
     * @return The number of bytes read, or -1 at EOF.
     */
    public int read( byte[] buf )
            throws IOException {
        return read( buf, 0, buf.length );
    }

    /**
     * Reads bytes from the current tar archive entry.
     * <p/>
     * This method is aware of the boundaries of the current
     * entry in the archive and will deal with them as if they
     * were this stream's start and EOF.
     *
     * @param buf       The buffer into which to place bytes read.
     * @param offset    The offset at which to place bytes read.
     * @param numToRead The number of bytes to read.
     *
     * @return The number of bytes read, or -1 at EOF.
     */
    public int read( byte[] buf, int offset, int numToRead )
            throws IOException {
        int totalRead = 0;

        if ( entryOffset >= entrySize ) {
            return -1;
        }

        if ( (numToRead + entryOffset) > entrySize ) {
            numToRead = (int) (entrySize - entryOffset);
        }

        if ( readBuf != null ) {
            int sz = (numToRead > readBuf.length)
                     ? readBuf.length : numToRead;

            System.arraycopy( readBuf, 0, buf, offset, sz );

            if ( sz >= readBuf.length ) {
                readBuf = null;
            } else {
                int newLen = readBuf.length - sz;
                byte[] newBuf = new byte[newLen];
                System.arraycopy( readBuf, sz, newBuf, 0, newLen );
                readBuf = newBuf;
            }

            totalRead += sz;
            numToRead -= sz;
            offset += sz;
        }

        for (; numToRead > 0; ) {
            byte[] rec = buffer.readRecord();
            if ( rec == null ) {
                // Unexpected EOF!
                throw new IOException( "unexpected EOF with " + numToRead + " bytes unread" );
            }

            int sz = numToRead;
            int recLen = rec.length;

            if ( recLen > sz ) {
                System.arraycopy( rec, 0, buf, offset, sz );
                readBuf = new byte[recLen - sz];
                System.arraycopy( rec, sz, readBuf, 0, recLen - sz );
            } else {
                sz = recLen;
                System.arraycopy( rec, 0, buf, offset, recLen );
            }

            totalRead += sz;
            numToRead -= sz;
            offset += sz;
        }

        entryOffset += totalRead;

        return totalRead;
    }

    /**
     * Copies the contents of the current tar archive entry directly into
     * an output stream.
     *
     * @param out The OutputStream into which to write the entry's data.
     */
    public void copyEntryContents( OutputStream out )
            throws IOException {
        byte[] buf = new byte[32 * 1024];

        for ( int numRead; -1 != (numRead = read( buf, 0, buf.length )); ) {
            if ( numRead != 0 ) {
                out.write( buf, 0, numRead );
            }
        }
    }
}


