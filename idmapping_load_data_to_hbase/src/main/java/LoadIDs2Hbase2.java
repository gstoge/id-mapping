import ids.IDs;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;

/**
 * Created by chentao on 16/6/24.
 */
public class LoadIDs2Hbase2 implements Tool {

    private String zkPath = "10.10.12.82,10.10.12.83,10.10.12.84";
    private String zkIdsPath = "/idmapping/active_ids";
    private ConnectWatcher connectWatcher = new ConnectWatcher();
    private String zkTableName = new String();

    public void setConf(Configuration configuration) {}
    public Configuration getConf() {
        return null;
    }

    public static class LoadIDs2HbaseMapper extends Mapper<AvroKey<IDs>, NullWritable, ImmutableBytesWritable, Put> {
        private byte[] familyIds = Bytes.toBytes("ids");
        private byte[] qualifier = Bytes.toBytes("value");
        private byte[] rowKey = null;
        private byte[] hValue = null;

        protected void map(AvroKey<IDs> key, NullWritable value, Context context) throws IOException, InterruptedException {

            String global_id = key.datum().getGlobalId();
            String ids = key.datum().toString();
            rowKey = Bytes.toBytes(global_id);
            ImmutableBytesWritable rowKeyWritable = new ImmutableBytesWritable(rowKey);
            Put put = new Put(rowKey);
            hValue = Bytes.toBytes(ids);
            put.addColumn(familyIds, qualifier, hValue);
            context.write(rowKeyWritable, put);
        }
    }

    public static byte[][] getHexSplits(String startKey, String endKey,
                                        int numRegions) {
        byte[][] splits = new byte[numRegions - 1][];
        BigInteger lowestKey = new BigInteger(startKey, 16);
        BigInteger highestKey = new BigInteger(endKey, 16);
        BigInteger range = highestKey.subtract(lowestKey);
        BigInteger regionIncrement = range.divide(BigInteger
                .valueOf(numRegions));
        lowestKey = lowestKey.add(regionIncrement);
        for (int i = 0; i < numRegions - 1; i++) {
            BigInteger key = lowestKey.add(regionIncrement.multiply(BigInteger
                    .valueOf(i)));
            byte[] b = String.format("%016x", key).getBytes();
            splits[i] = b;
        }
        return splits;
    }

    public int run(String[] strings) throws Exception {
        connectWatcher.connect(zkPath);
        zkTableName = "";
        zkTableName = connectWatcher.getData(zkIdsPath, null);
        zkTableName = zkTableName.equals("idmapping_ids_2")?"idmapping_ids_1":"idmapping_ids_2";
        Configuration conf = new Configuration();
        conf.set("mapreduce.job.queuename", "dmp");
        conf.set("mapreduce.job.name", "idmapping_load_ids_to_hbase");
//        conf.setInt("mapreduce.reduce.memory.mb", 2048);
//        conf.setInt("mapreduce.reduce.java.opts", 2048);
        Job job = new Job(conf);
        FileSystem fs = FileSystem.get(conf);
        Path output = new Path(strings[2]);
        if (fs.exists(output)) {
            fs.delete(output, true);//如果输出路径存在，就将其删除
        }
        job.setJarByClass(LoadIDs2Hbase2.class);
        job.setMapperClass(LoadIDs2HbaseMapper.class);
        job.setMapOutputKeyClass(ImmutableBytesWritable.class);
        job.setMapOutputValueClass(Put.class);
        FileInputFormat.addInputPath(job, new Path(strings[1]));
        FileOutputFormat.setOutputPath(job, new Path(strings[2]));
        AvroJob.setInputKeySchema(job, ids.IDs.getClassSchema());
        job.setInputFormatClass(AvroKeyInputFormat.class);
//        job.setOutputFormatClass(HFileOutputFormat2.class);
        Configuration hbaseConfiguration= HBaseConfiguration.create();
        hbaseConfiguration.set("mapreduce.job.queuename", "dmp");
        hbaseConfiguration.set("mapreduce.job.name", "idmapping-bulkload-ids" + strings[1]);
        hbaseConfiguration.set("hbase.zookeeper.quorum", zkPath);
        hbaseConfiguration.set("hbase.mapreduce.bulkload.max.hfiles.perRegion.perFamily", "1000");
        HBaseAdmin admin = new HBaseAdmin(hbaseConfiguration);
        HTableDescriptor td = admin.getTableDescriptor(Bytes.toBytes(zkTableName));
        admin.disableTable(zkTableName);
        admin.deleteTable(zkTableName);
        byte[][] splits = getHexSplits("000000000000000000", "ffffffffffffffffffff", 3411);
        admin.createTable(td, splits);
        HTable table = new HTable(hbaseConfiguration, zkTableName);
//        Connection connection = ConnectionFactory.createConnection(hbaseConfiguration);
//        TableName tableName = TableName.valueOf(zkTableName);
        HFileOutputFormat2.configureIncrementalLoad(job, table, table);
        int exitCode = job.waitForCompletion(true) == true ? 0 : 1;
        if (exitCode == 0) {
            LoadIncrementalHFiles loadFfiles = new LoadIncrementalHFiles(hbaseConfiguration);
            loadFfiles.doBulkLoad(new Path(strings[2]), table);//导入数据
            System.out.println("Bulk Load Completed..");
        }
//        connectWatcher.setData(zkIdsPath, zkTableName);
        connectWatcher.close();
        return  exitCode;
    }

    public static void main(String[] args) throws UnsupportedEncodingException {
        byte[][] splits = getHexSplits("0000", "FFFF", 10);
        for (byte[] split : splits) {
            String srt2 = new String(split, "UTF-8");
            System.out.println(srt2);
        }
    }
}
