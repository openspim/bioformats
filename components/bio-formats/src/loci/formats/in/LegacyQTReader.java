//
// LegacyQTReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ImageProducer;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import loci.formats.*;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;

/**
 * LegacyQTReader is a file format reader for QuickTime movie files.
 * To use it, QuickTime for Java must be installed.
 *
 * Much of this code was based on the QuickTime Movie Opener for ImageJ
 * (available at http://rsb.info.nih.gov/ij/plugins/movie-opener.html).
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/LegacyQTReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/LegacyQTReader.java">SVN</a></dd></dl>
 */
public class LegacyQTReader extends FormatReader {

  // -- Fields --

  /** Instance of LegacyQTTools to handle QuickTime for Java detection. */
  protected LegacyQTTools tools;

  /** Reflection tool for QuickTime for Java calls. */
  protected ReflectedUniverse r;

  /** Time offset for each frame. */
  protected int[] times;

  /** Image containing current frame. */
  protected Image image;

  // -- Constructor --

  /** Constructs a new QT reader. */
  public LegacyQTReader() { super("QuickTime", "mov"); }

  /** Constructs a new QT reader with the given id mappings. */
  public LegacyQTReader(Hashtable idMap) {
    super("QuickTime", "mov");
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessStream) */
  public boolean isThisType(RandomAccessStream stream) throws IOException {
    return false;
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    byte[] tmp = ImageTools.getBytes(openImage(no, x, y, w, h), false);
    System.arraycopy(tmp, 0, buf, 0, (int) Math.min(tmp.length, buf.length));
    return buf;
  }

  /* @see loci.formats.IFormatReader#openImage(int, int, int, int, int) */
  public BufferedImage openImage(int no, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.assertId(currentId, true, 1);
    FormatTools.checkPlaneNumber(this, no);

    // paint frame into image
    try {
      r.setVar("time", times[no]);
      r.exec("moviePlayer.setTime(time)");
      r.exec("qtip.redraw(null)");
      r.exec("qtip.updateConsumers(null)");
    }
    catch (ReflectException re) {
      throw new FormatException("Open movie failed", re);
    }
    return ImageTools.makeBuffered(image).getSubimage(x, y, w, h);
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    try {
      if (r.getVar("openMovieFile") != null) {
        r.exec("openMovieFile.close()");
        if (!fileOnly) {
          r.exec("m.disposeQTObject()");
          r.exec("imageTrack.disposeQTObject()");
          r.exec("QTSession.close()");
        }
      }
    }
    catch (ReflectException e) {
      IOException io = new IOException("Close movie failed");
      io.initCause(e);
      throw io;
    }
    if (!fileOnly) {
      currentId = null;
      times = null;
      image = null;
    }
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#close() */
  public void close() throws IOException {
    close(false);
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("LegacyQTReader.initFile(" + id + ")");

    status("Checking for QuickTime Java");

    if (tools == null) {
      tools = new LegacyQTTools();
      r = tools.getUniverse();
    }
    if (tools.isQTExpired()) {
      throw new FormatException(LegacyQTTools.EXPIRED_QT_MSG);
    }
    if (!tools.canDoQT()) {
      throw new FormatException(LegacyQTTools.NO_QT_MSG);
    }

    super.initFile(id);

    status("Reading movie dimensions");
    try {
      r.exec("QTSession.open()");

      // open movie file
      Location file = new Location(id);
      r.setVar("path", file.getAbsolutePath());
      r.exec("qtf = new QTFile(path)");
      r.exec("openMovieFile = OpenMovieFile.asRead(qtf)");
      r.exec("m = Movie.fromFile(openMovieFile)");

      int numTracks = ((Integer) r.exec("m.getTrackCount()")).intValue();
      int trackMostLikely = 0;
      int trackNum = 0;
      while (++trackNum <= numTracks && trackMostLikely == 0) {
        r.setVar("trackNum", trackNum);
        r.exec("imageTrack = m.getTrack(trackNum)");
        r.exec("d = imageTrack.getSize()");
        Integer w = (Integer) r.exec("d.getWidth()");
        if (w.intValue() > 0) trackMostLikely = trackNum;
      }

      r.setVar("trackMostLikely", trackMostLikely);
      r.exec("imageTrack = m.getTrack(trackMostLikely)");
      r.exec("d = imageTrack.getSize()");
      Integer w = (Integer) r.exec("d.getWidth()");
      Integer h = (Integer) r.exec("d.getHeight()");

      r.exec("moviePlayer = new MoviePlayer(m)");
      r.setVar("dim", new Dimension(w.intValue(), h.intValue()));
      ImageProducer qtip = (ImageProducer)
        r.exec("qtip = new QTImageProducer(moviePlayer, dim)");
      image = Toolkit.getDefaultToolkit().createImage(qtip);

      r.setVar("zero", 0);
      r.setVar("one", 1f);
      r.exec("timeInfo = new TimeInfo(zero, zero)");
      r.exec("moviePlayer.setTime(zero)");
      Vector v = new Vector();
      int time = 0;
      Integer q = new Integer(time);
      do {
        v.add(q);
        r.exec("timeInfo = imageTrack.getNextInterestingTime(" +
          "StdQTConstants.nextTimeMediaSample, timeInfo.time, one)");
        q = (Integer) r.getVar("timeInfo.time");
        time = q.intValue();
      }
      while (time >= 0);
      core[0].imageCount = v.size();
      times = new int[getImageCount()];
      for (int i=0; i<times.length; i++) {
        q = (Integer) v.elementAt(i);
        times[i] = q.intValue();
      }

      status("Populating metadata");

      BufferedImage img = ImageTools.makeBuffered(image);

      core[0].sizeX = img.getWidth();
      core[0].sizeY = img.getHeight();
      core[0].sizeZ = 1;
      core[0].sizeC = img.getRaster().getNumBands();
      core[0].sizeT = getImageCount();
      core[0].pixelType = ImageTools.getPixelType(img);
      core[0].dimensionOrder = "XYCTZ";
      core[0].rgb = true;
      core[0].interleaved = false;
      core[0].littleEndian = false;
      core[0].indexed = false;
      core[0].falseColor = false;

      MetadataStore store =
        new FilterMetadata(getMetadataStore(), isMetadataFiltered());
      store.setImageName("", 0);
      MetadataTools.setDefaultCreationDate(store, id, 0);
      MetadataTools.populatePixels(store, this);
    }
    catch (ReflectException e) {
      throw new FormatException("Open movie failed", e);
    }
  }

}