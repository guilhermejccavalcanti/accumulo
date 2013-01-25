/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.data;

import static org.apache.accumulo.core.data.Mutation.SERIALIZED_FORMAT.VERSION2;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.thrift.TMutation;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableUtils;

/**
 * Mutation that holds system time as computed by the tablet server when not provided by the user.
 */
public class ServerMutation extends Mutation {
  private long systemTime = 0l;
  
  public ServerMutation(TMutation tmutation) {
    super(tmutation);
  }

  public ServerMutation(Text key) {
    super(key);
  }

  public ServerMutation() {
  }

  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    // new format writes system time with the mutation
    if (getSerializedFormat() == VERSION2)
      systemTime = WritableUtils.readVLong(in);
    else {
      // old format stored it in the timestamp of each mutation
      for (ColumnUpdate upd : getUpdates()) {
        if (!upd.hasTimestamp()) {
          systemTime = upd.getTimestamp();
          break;
        }
      }
    }
  }
  
  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    WritableUtils.writeVLong(out, systemTime);
  }

  public void setSystemTimestamp(long v) {
    this.systemTime = v;
  }
  
  public long getSystemTimestamp() {
    return this.systemTime;
  }

  @Override
  protected ColumnUpdate newColumnUpdate(byte[] cf, byte[] cq, byte[] cv, boolean hasts, long ts, boolean deleted, byte[] val) {
    return new ServerColumnUpdate(cf, cq, cv, hasts, ts, deleted, val, this);
  }

  @Override
  public long estimatedMemoryUsed() {
    return super.estimatedMemoryUsed() + 8;
  }
}
