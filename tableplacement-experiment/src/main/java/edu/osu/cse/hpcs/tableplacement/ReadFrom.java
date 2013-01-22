package edu.osu.cse.hpcs.tableplacement;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.columnar.BytesRefArrayWritable;
import org.apache.hadoop.hive.serde2.columnar.ColumnarSerDeBase;
import org.apache.hadoop.hive.serde2.objectinspector.StandardStructObjectInspector;
import org.apache.hadoop.io.LongWritable;
import org.apache.log4j.Logger;

import edu.osu.cse.hpcs.tableplacement.column.Column;
import edu.osu.cse.hpcs.tableplacement.exception.TablePropertyException;
import edu.osu.cse.hpcs.tableplacement.multifile.MultiFileReader;
import edu.osu.cse.hpcs.tableplacement.multifile.RCFileMultiFileReader;

public abstract class ReadFrom {
  protected TableProperty prop;
  protected List<Column> columns;
  protected FileSystem fs;
  protected Configuration conf;
  protected Path inputDir;
  protected ColumnarSerDeBase serde;
  protected StandardStructObjectInspector rowHiveObjectInspector;
  protected int columnCount;
  protected String readColumnStr;
  protected ArrayList<Integer> readCols;
  protected long rowCount;
  protected Map<String, List<Integer>> readColumns;
  
  protected boolean isReadLocalFS;
  
  //Performance measures
 protected long totalRowReadTimeInNano;
 //protected long totalRowDeserializationTimeInNano;
  
  public ReadFrom(String propertyFilePath, String inputPath,
      Properties cmdProperties, Logger log) throws IOException, TablePropertyException,
      SerDeException, InstantiationException, IllegalAccessException,
      ClassNotFoundException {
    prop = new TableProperty(new File(propertyFilePath), cmdProperties);
    prop.prepareColumns();
    columns = prop.getColumnList();
    columnCount = columns.size();
    conf = new Configuration();
    prop.copyToHadoopConf(conf);
    //fs = FileSystem.getLocal(conf);
    inputDir = new Path(inputPath);
    fs = inputDir.getFileSystem(conf);
    
    isReadLocalFS = false;
    if (inputPath.toLowerCase().startsWith("file")) {
      isReadLocalFS = true;
    }
    
    readColumns = MultiFileReader.parseReadColumnMultiFileStr(
        prop.get(TableProperty.READ_COLUMN_STR));

    prop.dump();
  }
  
  public abstract long read() throws IOException, SerDeException, ClassNotFoundException, InstantiationException, IllegalAccessException, TablePropertyException;

  public long doRead(MultiFileReader reader, Logger log)
      throws IOException, SerDeException {
    long ts;
    assert totalRowReadTimeInNano == 0;
    //assert totalRowDeserializationTimeInNano == 0;

    Map<String, BytesRefArrayWritable> ret = new HashMap<String, BytesRefArrayWritable>();
    List<ColumnFileGroup> groups = reader.getColumnFileGroups();
    for (ColumnFileGroup group: groups) {
      if (!readColumns.keySet().contains(group.getName())) {
        continue;
      }
      BytesRefArrayWritable braw = new BytesRefArrayWritable(group.getColumns().size());
      braw.resetValid(group.getColumns().size());
      ret.put(group.getName(), braw);
    }
    LongWritable rowID = new LongWritable();
    rowCount = 0;
    long totalSerializedDataSize = 0;

    while (reader.next(rowID)) {
      ts = System.nanoTime();
      reader.getCurrentRow(ret);
      totalRowReadTimeInNano += System.nanoTime() - ts;
      for (Entry<String, BytesRefArrayWritable> entry: ret.entrySet()) {
        String groupName = entry.getKey();
        BytesRefArrayWritable braw = entry.getValue();
        //ts = System.nanoTime();
        //ColumnarSerDeBase serde = reader.getGroupSerDe(groupName);
        //serde.deserialize(braw);        
        //totalRowDeserializationTimeInNano += System.nanoTime() - ts;
        for (int j = 0; j < braw.size(); j++) {
          totalSerializedDataSize += braw.get(j).getLength();
        }
      }
      rowCount++;
    }
    reader.close();
    log.info("Row count : " + rowCount);
    log.info("Total serialized data size: " + totalSerializedDataSize);
    return totalSerializedDataSize;
  }
  
  public abstract String getFormatName();
  
  public void runTest() throws IOException, SerDeException, ClassNotFoundException, InstantiationException, IllegalAccessException, TablePropertyException {
    System.out.println("Reading data from " + getFormatName() + " ...");
    long start = System.nanoTime();
    long totalSerializedDataSize = read();
    long end = System.nanoTime();
    System.out.println("Reading from " + getFormatName() + " finished.");
    System.out
        .println("Total serialized data size (MiB): " + totalSerializedDataSize * 1.0 / 1024 / 1024);
    System.out.println("Total elapsed time: " + (end - start) / 1000000 + " ms");
    System.out.println("Total row read time: "
        + totalRowReadTimeInNano * 1.0 / 1000000 + " ms");
    System.out.println("Average row read time: "
        + totalRowReadTimeInNano * 1.0 / 1000000 / rowCount + " ms");
    //System.out.println("Total row deserialization time: "
    //    + totalRowDeserializationTimeInNano * 1.0 / 1000000 + " ms");
    //System.out.println("Average row deserialization time: "
    //    + totalRowDeserializationTimeInNano * 1.0 / 1000000 / rowCount + " ms");
    System.out.println("Throughput MiB/s: " + totalSerializedDataSize * 1.0
        / 1024 / 1024 / (end - start) * 1000000000);
  }
}