package com.isti.traceview.data;

import com.isti.traceview.TraceViewException;
import com.isti.traceview.common.Station;
import com.isti.traceview.common.TimeInterval;
import com.isti.traceview.common.TimeInterval.DateFormatType;
import com.isti.traceview.filters.IFilter;
import com.isti.traceview.processing.FilterFacade;
import com.isti.traceview.processing.Rotation;
import edu.iris.Fissures.IfNetwork.ChannelId;
import edu.iris.Fissures.IfNetwork.NetworkId;
import edu.iris.Fissures.IfTimeSeries.EncodedData;
import edu.iris.Fissures.Time;
import edu.iris.Fissures.model.MicroSecondDate;
import edu.iris.Fissures.model.SamplingImpl;
import edu.iris.dmc.seedcodec.SteimException;
import edu.iris.dmc.seedcodec.SteimFrameBlock;
import edu.sc.seis.fissuresUtil.mseed.FissuresConvert;
import edu.sc.seis.fissuresUtil.mseed.Recompress;
import edu.sc.seis.seisFile.mseed.DataRecord;
import edu.sc.seis.seisFile.mseed.SeedFormatException;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.apache.log4j.Logger;

//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;

/**
 * Class for trace representation, holds raw trace data and introduces an abstract way to get it.
 * Trace data here is list of {@link Segment}s.
 *
 * @author Max Kokoulin
 */
public class RawDataProvider extends Channel {

  /**
   *
   */
  private static final long serialVersionUID = 1L;

  private static final Logger logger = Logger.getLogger(RawDataProvider.class);

  protected final List<SegmentCache> rawData;

  private List<ContiguousSegmentRange> contiguousRanges;

  private boolean loadingStarted = false;
  private boolean loaded = false;

  protected boolean resetCaches = false;

  // Used to store dataStream file name and restore it after serialization
  private String serialFile = null;
  private transient RandomAccessFile serialStream = null;

  // Constructor 1 (multiple args)
  public RawDataProvider(String channelName, Station station, String networkName,
      String locationName) {
    super(channelName, station, networkName, locationName);
    rawData = new ArrayList<>();
    contiguousRanges = new ArrayList<>();
  }


  /**
   * Convenience method for loading in the raw data from a segment
   *
   * @param index Index of data to load in from list of segments
   * @return Array of ints representing raw timeseries data from trace
   */
  public int[] getUncutSegmentData(int index) {
    return rawData.get(index).getSegment().getData().data;
  }

  /**
   * Runnable class for loadData(TimeInterval ti) NOTE: This is currently not used due to hardware
   * constraints on different machines
   */
  @SuppressWarnings("unused")
  private static class LoadDataWorker implements Runnable {

    private final Segment segment;  // current segment to load
    int index;      // index of current segment

    // Constructor initializing channel segment
    private LoadDataWorker(Segment segment, int index) {
      this.segment = segment;
      this.index = index;
    }

    @Override
    public void run() {
      int sampleCount = segment.getSampleCount();
      if (!segment.getIsLoaded()) {
        logger.debug("== Load Segment:" + segment.toString());
        segment.load();
        segment.setIsLoaded(true);
      } else {
        logger.debug("== Segment is ALREADY loaded:" + segment.toString());
        //System.out.format("== RawDataProvider.loadData(): Segment is Already Loaded:%s\n", seg.toString() );
        // MTH: This is another place we *could* load the points into a serialized provider (from .DATA)
        //      in order to have the segment's int[] data filled before serialization, but we're
        //      doing this instead via PDP.initPointCache() --> PDP.pixelize(ti) --> Segment.getData(ti)
        //seg.loadDataInt();
      }
    }
  }

  /**
   * Getter of the property <tt>rawData</tt>
   *
   * @return Returns all raw data this provider contains.
   */
  public List<Segment> getRawData() {
    List<Segment> ret = new ArrayList<>();
    synchronized (rawData) {
      for (SegmentCache sc : rawData) {
        ret.add(sc.getSegment());
      }
      return ret;
    }
  }

  /**
   * @return Returns one point for given time value, or Integer.MIN_VALUE if value not found
   */
  public int getRawData(long time) {
    int index = findIndexOfSegmentContainingTime(time);
    if (index >= 0) {
      Segment segment = rawData.get(index).getSegment();
      return segment.getPointAtTime(time);
    } else {
      return Integer.MIN_VALUE;
    }
  }

  /**
   * binary search for a given time in this object's collection of data segments
   * @param time
   * @return
   */
  private int findIndexOfSegmentContainingTime(long time) {
    // Collections.sort(rawData);
    return findSegmentContainingTime(time, 0, rawData.size());
  }

  private int findSegmentContainingTime(long time, int lowerBound, int upperBound) {
    // base case
    if (upperBound - lowerBound <= 5) {
      if (time < rawData.get(lowerBound).getSegment().getStartTimeMillis()) {
        return -(lowerBound + 1);
      }
      for (int i = lowerBound; i < upperBound; ++i) {
        Segment seg = rawData.get(i).getSegment();
        if (time >= seg.getStartTimeMillis() && time < seg.getEndTimeMillis()) {
          return i;
        } else if (time < seg.getStartTimeMillis() &&
            time >= rawData.get(i-1).getSegment().getEndTimeMillis()) {
          return -(i + 1);
        }
      }
      return -(upperBound + 1);
    }

    int midPoint = ((upperBound - lowerBound) / 2) + lowerBound;
    Segment seg = rawData.get(midPoint).getSegment();
    if (time >= seg.getStartTimeMillis() && time < seg.getEndTimeMillis()) {
      return midPoint;
    } else if (time < seg.getStartTimeMillis()) {
      return findSegmentContainingTime(time, lowerBound, midPoint);
    } else {
      return findSegmentContainingTime(time, midPoint, upperBound);
    }
  }

  /**
   * @return Returns the raw data this provider contains for the time window.
   */
  public List<Segment> getRawData(TimeInterval ti) {
    List<Segment> ret = Collections.synchronizedList(new ArrayList<>());
    for (int i = 0; i < rawData.size(); ++i) {
      Segment seg = rawData.get(i).getSegment();
      if ((seg != null) && ti.isIntersect(
          new TimeInterval(seg.getStartTime(), seg.getEndTime()))) {
        ret.add(seg);
      } else if (seg.getStartTimeMillis() > ti.getEnd()) {
        break;
      }
    }
    return ret;
  }

  /**
   * Get rotated data for a given time interval
   *
   * @param rotation to process data
   * @return rotated raw data
   */
  public List<Segment> getRawData(Rotation rotation, TimeInterval ti) {
    if (rotation != null) {
      try {
        return rotation.rotate(this, ti);
      } catch (TraceViewException e) {
        logger.error("TraceViewException:", e);
        return null;
      }
    }

    return getRawData(ti);
  }

  public int getDataLength(TimeInterval ti) {
    int dataLength = 0;
    for (Segment segment : getRawData(ti)) {
      dataLength += segment.getData(ti).data.length;
    }
    return dataLength;
  }

  /**
   * @return count of {@link Segment}s this provider contains
   */
  public int getSegmentCount() {
    return rawData.size();
  }

  /**
   * Add segment to raw data provider
   *
   * @param segment to add
   */
  public void addSegment(Segment segment) {
    resetCaches = false;
    if (segment.getSampleCount() < 1) {
      logger.warn("Segment has no usable data!");
      return;
    }
    synchronized (rawData) {
      boolean notContinuous = rawData.size() == 0 ||
          Segment.isDataBreak(rawData.get(rawData.size() - 1).getSegment().getEndTimeMillis(),
              segment.getStartTimeMillis(), segment.getSampleRate());
      rawData.add(new SegmentCache(segment));
      int newestSegmentIndex = rawData.size() - 1;
      segment.setRawDataProvider(this);

      if (contiguousRanges.size() == 0 || notContinuous) {
        contiguousRanges.add(
            new ContiguousSegmentRange(newestSegmentIndex, newestSegmentIndex,
                segment.getStartTimeMillis()));
      } else {
        contiguousRanges.get(contiguousRanges.size() - 1).setEndingIndex(newestSegmentIndex);
      }
    }
    setSampleRate(segment.getSampleRate());
    logger.debug(segment + " added to " + this);
  }

  protected List<SegmentCache> getSegmentCache() {
    return rawData;
  }

  /**
   * Add in segments from a RawDataProvider in order to handle possible gaps in current data.
   * This prevents issues caused by loading a multi-day SEED file over an file with the same data,
   * or other cases where existing data might overlap with what is being loaded in
   * @param mergeIn New seed file to load in
   */
  public void mergeData(RawDataProvider mergeIn) {
    if (!mergeIn.equals(this)) {
      // if the channels don't have the same SNCL, then don't try to merge them
      return;
    }

    resetCaches = false;
    synchronized (rawData) {
      List<SegmentCache> segments = mergeIn.getSegmentCache();
      // now go through the data we're merging in and see if they overlap/duplicate
      sort(); // do full sort to ensure contiguous segment list is correct
      // i.e., this allows us to do binary search operations on the data
      outerLoop:
      for (SegmentCache cachedSegment : segments) {
        Segment segment = cachedSegment.getSegment();
        // end time of data refers to the point at which the next sample would be taken
        // so if a segment ends at the same time that another one begins, they are a continuous
        // trace over that length of time. as a result we can perform trim operations by setting
        // the data-to-merge's start and end times to the end and start of data between gaps

        SegmentCache cache = new SegmentCache(segment);
        // binary search for the new index (requires list to be sorted)

        int expectedIndex = Collections.binarySearch(rawData, cache);
        if (expectedIndex >= 0) {

          // if index >= 0, a segment with the given start time already exists
          Segment testSegment = rawData.get(expectedIndex).getSegment();

          if (segment.getEndTime().getTime() <= testSegment.getEndTime().getTime()) {
            // start times must match for index to be positive; in this case either segment is
            // the exact same length or shorter than the data already there, so we skip over it
            continue; // nothing else to do here -- just go on to the next segment
          }

          // trim off the part duplicated (go one sample after the segment in the list)
          long newStart = testSegment.getEndTime().getTime();
          segment = new Segment(segment, newStart, segment.getEndTime().getTime());
          // we will add any data in this segment that doesn't overlap what exists soon
        } else {
          // if index < 0
          // first we will manipulate the data to get the location where the segment SHOULD be
          // the returned value is (expectedInsertionPoint - 1) * -1, so just invert that
          expectedIndex = -1 * (expectedIndex + 1);
          if (expectedIndex == rawData.size()) {
            // this might happen if the data is past any existing segments
            addSegment(segment); // no conflicts with existing data
            continue; // we've added everything in this segment, so move to the next one
          }

          // if this isn't going to be in the first place in the list, we need to prevent collision
          // with whatever value came before it
          // only need to check the one, because anything else can't collide with it
          // (otherwise the binary search would return a different index)
          if (expectedIndex > 0) {
            Segment previousInList = rawData.get(expectedIndex - 1).getSegment();
            if (!segment.getStartTime().after(previousInList.getEndTime())) {
              // start at the end of the found segment's end time -- don't overwrite existing data
              long newStart = previousInList.getEndTime().getTime();
              // trim off the data that's already duplicated -- i.e., get a new Segment
              segment = new Segment(segment, newStart, segment.getEndTime().getTime());
            }
          }
          // now that we've accounted for any possible previous overlap, time to fill in the gap
          // between the next data point's start and the current data we're examining
          // now this segment must also have range between that segment and the next one
          // so let's get that range and add it to the segment list
          // gap ends the sample before the next point in the list
          long gapEnd = rawData.get(expectedIndex).getSegment().getStartTime().getTime();
          // just make sure that this segment doesn't overlap the data either
          gapEnd = Math.min(gapEnd, segment.getEndTime().getTime());
          Segment trimmedSegment = new Segment(segment, segment.getStartTime().getTime(), gapEnd);
          if (trimmedSegment.getSampleCount() > 0) {
            // this is almost certainly guaranteed to be true, admittedly
            addSegment(trimmedSegment);
          }
          // now our range of analysis is for the points after the given segment
          long afterExisting = rawData.get(expectedIndex).getSegment().getEndTime().getTime();
          segment = new Segment(segment, afterExisting, segment.getEndTime().getTime());
        }

        // now keep going through the list of existing data until this segment doesn't overlap
        for (int i = expectedIndex; i < rawData.size(); ++i) {

          // make sure there's still data in the list
          if (segment.getSampleCount() == 0) {
            continue outerLoop; // segment is now empty, so go on to the next one
          }

          // there is a gap here between the end of the previous point
          // which the segment currently has accounted for in its present start point
          long gapEnd = rawData.get(i).getSegment().getStartTime().getTime();
          Segment fillingPossibleGap =
              new Segment(segment, segment.getStartTime().getTime(), gapEnd);
          if (fillingPossibleGap.getSampleCount() > 0) {
            addSegment(fillingPossibleGap);
          }
          // now it's time to trim the segment again
          long newSegmentStart = rawData.get(i).getSegment().getEndTime().getTime();
          segment = new Segment(segment, newSegmentStart, segment.getEndTime().getTime());
        } // end of loop over rest of rawData

      } // end of loop over merged-in segments
    }

  }

  /**
   * @return time range of contained data
   */
  public TimeInterval getTimeRange() {
    synchronized (rawData) {
      if (rawData.size() == 0) {
        return null;
      } else {
        if (!sorted()) {
          sort();
        }
        return new TimeInterval(rawData.get(0).getSegment().getStartTime(),
            rawData.get(rawData.size() - 1).getSegment().getEndTime());
      }
    }
  }

  public boolean sorted() {
    for (int i = 1; i < contiguousRanges.size(); ++i) {
      if (contiguousRanges.get(i-1).compareTo(contiguousRanges.get(i)) > 0) {
        return false;
      }
    }
    long start = rawData.get(0).getSegment().getStartTimeMillis();
    long end = rawData.get(rawData.size() - 1).getSegment().getEndTimeMillis();
    return start < end;
  }

  /**
   * @return max raw data value on whole provider
   */
  public int getMaxValue() {
    int ret = Integer.MIN_VALUE;
    for (SegmentCache cached : rawData) {
      Segment segment = cached.getSegment();
      if (segment.getMaxValue() > ret) {
        ret = segment.getMaxValue();
      }
    }
    return ret;
  }

  /**
   * @return min raw data value on whole provider
   */
  public int getMinValue() {
    int ret = Integer.MAX_VALUE;
    for (SegmentCache cached : rawData) {
      Segment segment = cached.getSegment();
      if (segment.getMinValue() < ret) {
        ret = segment.getMinValue();
      }
    }
    return ret;
  }

  /**
   * @return flag if data loading process was started for this provider
   */
  public boolean isLoadingStarted() {
    synchronized (rawData) {
      return loadingStarted;
    }
  }

  /**
   * @return flag is data provider loaded
   */
  public boolean isLoaded() {
    synchronized (rawData) {
      return loaded;
    }
  }

  /**
   * Load data into this data provider from data sources
   *
   * NOTE: Removed multithreading due to hardware constraints
   * and clean this method.
   *
   * @param ti The TimeInterval to load
   */
  private void loadData(TimeInterval ti) {
    rawData.parallelStream()
        .filter(e -> !e.getSegment().getIsLoaded())
        .forEach(e -> {
          e.getSegment().load();
          e.getSegment().setIsLoaded(true);
        });
    // sort();
  }

  /**
   * @return list of data sources
   */
  public List<ISource> getSources() {
    List<ISource> ret = new ArrayList<>();
    for (SegmentCache sc : rawData) {
      ret.add(sc.getSegment().getDataSource());
    }
    return ret;
  }

  /**
   * Sets data stream to serialize this provider
   *
   * @param dataStream The serialized file set
   */
  public void setDataStream(Object dataStream) {
    logger.debug("== ENTER");
    try {
      if (dataStream == null) {
        logger.debug("== dataStream == null --> serialStream.close()");
        try {
          this.serialStream.close();
          this.serialStream = null;
        } catch (IOException e) {
          // do nothing
          logger.error("IOException:", e);
        }
      } else {
        if (dataStream instanceof String) {
          this.serialFile = (String) dataStream;
          logger.debug("dataStream == instanceof String --> set serialFile=" + serialFile);
        }
        // MTH: This is a little redundant since readObject() already wraps the serialFile in a
        //      BufferedRandomAccessFile before using it to call setDataStream(raf) ...
        this.serialStream = new RandomAccessFile(serialFile, "rw");
      }
      for (SegmentCache sc : rawData) {
        logger.debug("== sc.setDataStream(serialStream)");
        sc.setDataStream(serialStream);
      }
      logger.debug("== DONE");
    } catch (FileNotFoundException e) {
      logger.error("FileNotFoundException:", e);
    }
  }

  /**
   * @return data stream where this provider was serialized
   */
  public RandomAccessFile getDataStream() {
    return serialStream;
  }

  /**
   * @return flag if this provider was serialized
   */
  public boolean isSerialized() {
    return serialStream == null;
  }

  /**
   * Loads all data to this provider from its data sources
   */
  public void load() {
    if (loaded) return;

    loadingStarted = true;
    loadData(null);
    loaded = true;
    setChanged();
    notifyObservers(getTimeRange());
  }

  /**
   * Loads data inside given time interval to this provider from its data sources
   */
  public void load(TimeInterval ti) {
    loadingStarted = true;
    loadData(ti);
    loaded = true;
    setChanged();
    notifyObservers(getTimeRange());
  }

  /**
   * Dumps content of this provider in miniseed format
   *
   * @param ds stream to dump
   * @param ti content's time interval
   * @param filter filter being applied to the data
   * @param rotation system of rotation applied to the data (can be null)
   * @throws IOException if there are problems writing the miniseed dump
   */
  @SuppressWarnings("unchecked")
  public void dumpMseed(DataOutputStream ds, TimeInterval ti, IFilter filter, Rotation rotation)
      throws IOException {

    Segment previousSegment = null;
    List<Segment> segments = getRawData(rotation, ti);
    for (int j = 0; j < segments.size(); j++) {
      if (j > 0) {
        previousSegment = segments.get(j - 1);
      }
      Segment segment = segments.get(j);
      if (filter != null && previousSegment != null &&
          Segment.isDataGap(previousSegment.getEndTimeMillis(), segment.getStartTimeMillis(),
              segment.getSampleRate())) {
        // reset the filter due to data gap -- data is not continuous
        filter.init(this);
      }

      long currentTime = Math.max(ti.getStart(), segment.getStartTime().getTime());
      if (previousSegment != null) {
        currentTime = Math.max(currentTime, previousSegment.getEndTime().getTime());
      }
      TimeInterval dataInterval = new TimeInterval(currentTime, ti.getEnd());
      int[] data = segment.getData(dataInterval).data;
      if (filter != null) {
        data = new FilterFacade(filter, this).filter(data);
      }
      TimeInterval exportedRange = TimeInterval
          .getIntersect(ti, new TimeInterval(segment.getStartTime(), segment.getEndTime()));
      if (data.length > 0) {
        try {
          List<SteimFrameBlock> lst = Recompress.steim1(data);

          EncodedData[] edata = new EncodedData[lst.size()];
          for (int i = 0; i < edata.length; i++) {
            //(SteimFrameBlock)
            SteimFrameBlock block = lst.get(i);
            edata[i] = new EncodedData((short) 10, block.getEncodedData(), block.getNumSamples(),
                false);
          }
          Time channelStartTime = new Time(
              FissuresConvert.getISOTime(FissuresConvert.getBtime(new MicroSecondDate(exportedRange
                  .getStartTime()))), 0);

          LinkedList<DataRecord> dataRecords = FissuresConvert
              .toMSeed(edata, new ChannelId(new NetworkId(getNetworkName(),
                      channelStartTime), getStation().getName(), getLocationName(), getChannelName(),
                      channelStartTime), new MicroSecondDate(
                      exportedRange.getStartTime()),
                  new SamplingImpl(data.length, new edu.iris.Fissures.model.TimeInterval(
                      new MicroSecondDate(exportedRange.getStartTime()),
                      new MicroSecondDate(exportedRange.getEndTime()))), 1);

          for (DataRecord rec : dataRecords) {
            rec.write(ds);
          }
        } catch (SteimException | SeedFormatException e) {
          logger.error("Can't encode data: " + ti + ", " + this + e);
        }
      }

    }
  }

  /**
   * Dumps content of this provider in ASCII format
   *
   * @param fw writer to dump
   * @param ti content's time interval
   * @throws IOException if there are problems writing the ascii dump
   */
  public void dumpASCII(FileWriter fw, TimeInterval ti, IFilter filter, Rotation rotation)
      throws IOException {
    int i = 1;
    List<Segment> segments = getRawData(rotation, ti);
    Segment previousSegment = null;
    for (int j = 0; j < segments.size(); j++) {
      if (j > 0) {
        previousSegment = segments.get(j - 1);
      }
      Segment segment = segments.get(j);
      if (filter != null && previousSegment != null &&
          Segment.isDataGap(previousSegment.getEndTimeMillis(), segment.getStartTimeMillis(),
              segment.getSampleRate())) {
        // reset the filter due to data gap -- data is not continuous
        filter.init(this);
      }

      double sampleRate = segment.getSampleRate();
      long currentTime = Math.max(ti.getStart(), segment.getStartTime().getTime());
      if (previousSegment != null) {
        currentTime = Math.max(currentTime, previousSegment.getEndTime().getTime());
      }
      TimeInterval dataInterval = new TimeInterval(currentTime, ti.getEnd());
      int[] data = segment.getData(dataInterval).data;
      if (filter != null) {
        data = new FilterFacade(filter, this).filter(data);
      }
      for (int value : data) {
        if (ti.isContain(currentTime)) {
          fw.write(i + " " + TimeInterval
              .formatDate(new Date(currentTime), DateFormatType.DATE_FORMAT_NORMAL)
              + " " + value
              + "\n");
        }
        currentTime = (long) (currentTime + sampleRate);
      }
      i++;
    }
  }

  /**
   * Dumps content of this provider in XML format
   *
   * @param fw writer to dump
   * @param ti content's time interval
   * @throws IOException if there are problems writing the XML dump
   */
  public void dumpXML(FileWriter fw, TimeInterval ti, IFilter filter, Rotation rotation)
      throws IOException {
    @SuppressWarnings("unused")
    int i = 1;
    fw.write("<Trace network=\"" + getNetworkName() + "\" station=\"" + getStation().getName()
        + "\" location=\"" + getLocationName()
        + "\" channel=\"" + getChannelName() + "\">\n");
    List<Segment> segments = getRawData(rotation, ti);
    Segment previousSegment = null;
    for (int j = 0; j < segments.size(); j++) {
      if (j > 0) {
        previousSegment = segments.get(j - 1);
      }
      Segment segment = segments.get(j);
      if (filter != null && previousSegment != null &&
          Segment.isDataGap(previousSegment.getEndTimeMillis(), segment.getStartTimeMillis(),
              segment.getSampleRate())) {
        // reset the filter due to data gap -- data is not continuous
        filter.init(this);
      }

      long currentTime = Math.max(ti.getStart(), segment.getStartTime().getTime());
      if (previousSegment != null) {
        currentTime = Math.max(currentTime, previousSegment.getEndTime().getTime());
      }
      TimeInterval dataInterval = new TimeInterval(currentTime, ti.getEnd());
      int[] data = segment.getData(dataInterval).data;
      boolean segmentStarted = false;
      for (int value : data) {
        if (ti.isContain(currentTime)) {
          if (!segmentStarted) {
            fw.write("<Segment start =\""
                + TimeInterval
                .formatDate(new Date(currentTime), TimeInterval.DateFormatType.DATE_FORMAT_NORMAL)
                + "\" sampleRate = \"" + segment.getSampleRate() + "\">\n");
            segmentStarted = true;
          }
          fw.write("<Value>" + value + "</Value>\n");
        }
        currentTime = (long) (currentTime + segment.getSampleRate());
      }
      i++;
      fw.write("</Segment>\n");
    }
    fw.write("</Trace>\n");
  }

  /**
   * Dumps content of this provider in SAC format
   *
   * @param ds writer to dump
   * @param ti content's time interval
   * @throws IOException if there are problems writing the sac dump
   */
  public void dumpSacAscii(DataOutputStream ds, TimeInterval ti, IFilter filter, Rotation rotation)
      throws IOException, TraceViewException {
    List<Segment> segments = getRawData(rotation, ti);
    if (segments.size() != 1) {
      throw new TraceViewException("You have gaps in the interval to import as SAC");
    }
    int[] intData = segments.get(0).getData(ti).data;
    long currentTime = Math.max(ti.getStart(), segments.get(0).getStartTime().getTime());
    if (filter != null) {
      intData = new FilterFacade(filter, this).filter(intData);
    }
    float[] floatData = new float[intData.length];
    for (int i = 0; i < intData.length; i++) {
      floatData[i] = (float) intData[i];
    }
    SacTimeSeriesASCII sacAscii = SacTimeSeriesASCII.getSAC(this, new Date(currentTime), floatData);
    sacAscii.writeHeader(ds);
    sacAscii.writeData(ds);
  }

  /**
   * @return string representation of data provider in debug purposes
   */
  public String toString() {
    return "RawDataProvider:" + super.toString();
  }

  public void sortRawData() {
    if (sorted()) return;

    synchronized (rawData) {
      Collections.sort(contiguousRanges);
      List<SegmentCache> sortedSegmentCache = new ArrayList<>(rawData.size());
      for (ContiguousSegmentRange segmentRange : contiguousRanges) {
        int lowerIndex = segmentRange.startingIndex;
        int upperIndex = segmentRange.getEndingIndex();
        // note that upperIndex IS INCLUSIVE here
        for (int i = lowerIndex; i <= upperIndex; ++i) {
          sortedSegmentCache.add(rawData.get(i));
        }
      }
      for (int i = 0; i < sortedSegmentCache.size(); ++i) {
        rawData.set(i, sortedSegmentCache.get(i));
      }
    }
  }


  /**
   * Sorts data provider after loading
   */
  public void sort() {
    // empty data is already sorted -- will happen on first load operation after construction
    if (rawData.size() == 0)
      return;

    sortRawData();
    // now we reset contiguous ranges if we need to do more sorting in the future
    contiguousRanges = new ArrayList<>();
    contiguousRanges.add(
        new ContiguousSegmentRange(0, 0, rawData.get(0).getSegment().getStartTimeMillis()));
    // Collections.sort(rawData);
    // setting channel serial numbers in segments
    Segment previousSegment = null;
    int segmentNumber = 0;
    int sourceNumber = 0;
    int continueAreaNumber = 0;
    // rawData should already be in order at this point
    for (int i = 0; i < rawData.size(); i++) {
      Segment segment = rawData.get(i).getSegment();
      if (previousSegment != null) {
        if (Segment
            .isDataBreak(previousSegment.getEndTime().getTime(), segment.getStartTime().getTime(),
                segment.getSampleRate())) {
          segmentNumber++;
        }
        if (Segment
            .isDataGap(previousSegment.getEndTime().getTime(), segment.getStartTime().getTime(),
                segment.getSampleRate())) {
          continueAreaNumber++;
        }
        if (!previousSegment.getDataSource().equals(segment.getDataSource())) {
          sourceNumber++;
        }
      }
      if (segmentNumber == contiguousRanges.size()) {
        contiguousRanges.add(
            new ContiguousSegmentRange(i, i, segment.getStartTimeMillis()));
      } else {
        contiguousRanges.get(segmentNumber).setEndingIndex(i);
      }
      previousSegment = segment;
      segment.setChannelSerialNumber(segmentNumber);
      segment.setSourceSerialNumber(sourceNumber);
      segment.setContinueAreaNumber(continueAreaNumber);
    }
  }

  /**
   * Prints RawDataProvider content
   */
  public void printout() {
    System.out.println("  " + toString());
    for (SegmentCache cached : rawData) {
      Segment segment = cached.getSegment();
      System.out.println("    " + segment.toString());
    }
  }

  /**
   * Standard comparator - by start time
   */
  public int compareTo(Object o) {
    if (this.equals(o)) {
      return 0;
    }
    if (o == null) {
      return -1;
    }
    if (o instanceof RawDataProvider) {
      RawDataProvider rdd = (RawDataProvider) o;
      if (getTimeRange().getStart() > rdd.getTimeRange().getStart()) {
        return 1;
      } else {
        return -1;
      }
    } else {
      return -1;
    }
  }

  /**
   * Get name of file to serialize this RawDataProvider in the temporary storage
   *
   * @return the file name with ".SER" appended
   * @deprecated This method appears to no be used by anything.
   */
  public String getSerialFileName() {
    return getName() + ".SER";
  }

  /**
   * Special serialization handler
   *
   * @param out stream to serialize this object
   * @throws IOException if thrown in {@link java.io.ObjectOutputStream#defaultWriteObject()}
   * @see Serializable
   * @deprecated This method appears to no be used by anything.
   */
  private void writeObject(ObjectOutputStream out) throws IOException {
    logger.debug("== ENTER");
    logger.debug("Serializing RawDataProvider" + toString());
    out.defaultWriteObject();
    logger.debug("== EXIT");
  }

  /**
   * Special deserialization handler
   *
   * @param in stream to deserialize object
   * @throws IOException if thrown in {@link java.io.ObjectInputStream#defaultReadObject()}
   * @throws ClassNotFoundException if thrown in {@link java.io.ObjectInputStream#defaultReadObject()}
   * @see Serializable
   * @deprecated This method appears to no be used by anything.
   */
  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    logger.debug("== Deserializing RawDataProvider" + toString());
    // MTH: Once we've read in the .SER file, serialFile(=... .DATA) will be set
    logger.debug("== call defaultReadObject()");
    in.defaultReadObject();
    logger.debug("== defaultReadObject() DONE");
    if (serialFile != null) {
      serialStream = new RandomAccessFile(serialFile, "rw");
      setDataStream(serialStream);
    }
    logger.debug("== EXIT");
  }

  static class ContiguousSegmentRange implements Comparable<Object> {
    public final int startingIndex;
    public final long startEpochMillis;
    private int endingIndex;

    ContiguousSegmentRange(int start, int end, long startEpochMillis) {
      startingIndex = Math.min(start, end);
      endingIndex = Math.max(start, end);
      this.startEpochMillis = startEpochMillis;
    }

    public int getEndingIndex() {
      return endingIndex;
    }

    public void setEndingIndex(int endingIndex) {
      this.endingIndex = endingIndex;
    }

    public String toString() {
      return String.valueOf(startEpochMillis);
    }

    @Override
    public int compareTo(Object o) {
      if (this.equals(o)) {
        return 0;
      }
      if (o == null) {
        return -1;
      }
      if (o instanceof ContiguousSegmentRange) {
        ContiguousSegmentRange csr = (ContiguousSegmentRange) o;
        if (this.startEpochMillis > csr.startEpochMillis) {
          return 1;
        } else if (this.startEpochMillis == csr.startEpochMillis) {
          return 0;
        }
      }
      return -1;
    }
  }
}

/**
 * internal class to hold original segment (cache(0)) and it's images processed by filters. Also
 * may define caching policy.
 */
class SegmentCache implements Serializable, Comparable<Object> {

  /**
   *
   */
  private static final long serialVersionUID = 1L;
  private Segment initialData;

  SegmentCache(Segment segment) {
    initialData = segment;
  }

  /**
   * Setter for raw data
   *
   * @param segment The new initial data
   */
  @SuppressWarnings("unused")  //Why is this here?
  public void setData(Segment segment) {
    initialData = segment;
  }

  /**
   * Sets data stream to serialize this SegmentCache
   *
   * @param dataStream stream to serialize
   */
  public void setDataStream(RandomAccessFile dataStream) {
    initialData.setDataStream(dataStream);
  }

  /**
   * Getter for segment with raw, unprocessed data
   *
   * @return the initial data
   */
  public Segment getSegment() {
    return initialData;
  }

  /**
   * Standard comparator - by start time
   */
  public int compareTo(Object o) {
    if (this.equals(o)) {
      return 0;
    }
    if (o == null) {
      return -1;
    }
    if (o instanceof SegmentCache) {
      SegmentCache sc = (SegmentCache) o;
      if (getSegment().getStartTime().getTime() > sc.getSegment().getStartTime().getTime()) {
        return 1;
      } else if (getSegment().getStartTime().getTime() == sc.getSegment().getStartTime()
          .getTime()) {
        return 0;
      }
    }
    return -1;
  }

}

