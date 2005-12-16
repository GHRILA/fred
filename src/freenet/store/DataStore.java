/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.store;

import freenet.keys.Key;
import freenet.support.Logger;

import java.io.*;
import java.util.*;

public class DataStore extends Store {

    public class ATimeComparator implements Comparator {

		public int compare(Object arg0, Object arg1) {
			DataBlock db0 = (DataBlock) arg0;
			DataBlock db1 = (DataBlock) arg1;
			long a0 = db0.getLastAccessTime();
			long a1 = db1.getLastAccessTime();
			if(a0 < a1) return -1;
			if(a0 > a1) return 1;
			return 0;
		}

	}

	public static final String VERSION = "$Id: DataStore.java,v 1.5 2005/08/20 21:21:21 amphibian Exp $";    

	private RandomAccessFile _index;
	private final int blockSize;

	public DataStore(RandomAccessFile indexFile, RandomAccessFile dataFile, int blockSize, long maxBlocks) throws Exception {
	    super(dataFile, maxBlocks);
	    _index = indexFile;
	    this.blockSize = blockSize;
	    readStore();
	}
	
	public DataStore(File index, File data, int blockSize, long maxBlocks) throws Exception {
		super(data, maxBlocks);
		_index = new RandomAccessFile(index, "rw");
		this.blockSize = blockSize;
		readStore();
	}

	public synchronized void shutdown(boolean exit) throws IOException {
		super.shutdown(exit);
		_index.close();
	}
	
	protected synchronized void readStore() throws IOException {
		_index.seek(0);
		int recordNum = 0;

		Vector v = new Vector();
		
		try {
		while (_index.getFilePointer() < _index.length()) {

		    Key k = Key.read(_index);
		    long atime = _index.readLong();
			DataBlock dataBlock = new DataBlock(recordNum,
			    k, atime);

			getKeyMap().put(dataBlock.getKey(), dataBlock);
			getRecordNumberList().add(recordNum, dataBlock);
			v.add(dataBlock);
			recordNum++;
		}
		} catch (EOFException e) {
			// Chopped off in the middle of a key
			Logger.normal(this, "Store index truncated");
			return;
		} finally {
			DataBlock[] blocks = (DataBlock[]) v.toArray(new DataBlock[v.size()]);
			Arrays.sort(blocks, new ATimeComparator());
			for(int i=0;i<blocks.length;i++) {
				updateLastAccess(blocks[i]);
			}
		}
	}

	protected void deleteBlock(Block block, boolean wipeFromDisk) throws IOException {
		DataBlock dataBlock = (DataBlock) block;
		getKeyMap().remove(dataBlock.getKey());
		getAccessTimeList().remove(dataBlock);
		if (wipeFromDisk) {
			DataBlock lastDataBlock = getLastBlock();
			setRecordNumber(dataBlock.getRecordNumber(), lastDataBlock);
			_index.setLength(_index.length() - DataBlock.SIZE_ON_DISK);
			getBlockStore().setLength(getBlockStore().length() - blockSize);
		} else {
			getRecordNumberList().remove(dataBlock.getRecordNumber());
		}
	}

	public synchronized void addDataAsBlock(Key key, byte[] data) throws IOException {
		if (getKeyMap().containsKey(key)) {
		    Logger.minor(this, "Already have key: "+key);
			return;
		}

		if (_index.length() / DataBlock.SIZE_ON_DISK < getMaxBlocks()) {
			int recnum = (int) (_index.length() / DataBlock.SIZE_ON_DISK);
			createAndOverwrite(recnum, key, data);
		} else {
			DataBlock oldest = (DataBlock) getAccessTimeList().getFirst();
			deleteBlock(oldest, false);

			int recNo = oldest.getRecordNumber();
			createAndOverwrite(recNo, key, data);
		}
	}

	/**
	 * Moves this record to a new position, overwriting whatever on-disk data was there previously, but not deleting the
	 * old on-disk data for this record.
	 *
	 */
	protected void setRecordNumber(int newRecNo, DataBlock dataBlock) throws IOException {
		if (newRecNo == dataBlock.getRecordNumber()) {
			return;
		}
		_index.seek((long)newRecNo * DataBlock.SIZE_ON_DISK);
		dataBlock.getKey().write(_index);
		_index.writeLong(dataBlock.getLastAccessTime());

		byte[] ba = new byte[blockSize];
		getBlockStore().seek(dataBlock.positionInDataFile());
		getBlockStore().readFully(ba);
		getBlockStore().seek((long)newRecNo * blockSize);
		getBlockStore().write(ba);

		getRecordNumberList().remove(dataBlock.getRecordNumber());
		dataBlock.setRecordNumber(newRecNo);
		getRecordNumberList().add(newRecNo, dataBlock);


	}

	/**
	 * Creates a new block, overwriting the data on disk for an existing block
	 * (but *not* deleting that block from RAM)
	 */
	private void createAndOverwrite(int recnum, Key key, byte[] data) throws IOException {
	    Logger.minor(this, "createAndOverwrite("+recnum+","+key+")");
		DataBlock b = new DataBlock(recnum, key, System.currentTimeMillis());
		_index.seek((long)recnum * DataBlock.SIZE_ON_DISK);

		key.write(_index);
		getKeyMap().put(key, b);

		_index.writeLong(b.getLastAccessTime());
		getAccessTimeList().addLast(b);

		getBlockStore().seek((long)recnum * blockSize);
		getBlockStore().write(data);
		getRecordNumberList().add(recnum, b);
	}

	public synchronized byte[] getDataForBlock(Key key, boolean dontPromote) throws IOException {
		DataBlock b = getBlockByKey(key);
		if (b == null) {
			return null;
		} else {
		    Logger.minor(this, "Reading block: "+b.getRecordNumber());
			return readData(b, dontPromote);
		}
	}

	public Set getAllKeys() { 
		return ((Map)getKeyMap().clone()).keySet();
	}
	
	private byte[] readData(DataBlock dataBlock, boolean dontPromote) throws IOException {
		byte[] ba = new byte[blockSize];
		getBlockStore().seek(dataBlock.positionInDataFile());
		getBlockStore().readFully(ba);
		dataBlock.setLastAccessTime(System.currentTimeMillis()) ;

		if(!dontPromote) {
			getAccessTimeList().remove(dataBlock);
			getAccessTimeList().addLast(dataBlock);
			_index.seek(dataBlock.positionInIndexFile() + DataBlock.KEY_SIZE);
			_index.writeLong(dataBlock.getLastAccessTime());
		}
		return ba;

	}

	
	private DataBlock getBlockByKey(Key key) {
		return (DataBlock) getKeyMap().get(key);
	}

	public DataBlock getLastBlock() {
		return (DataBlock) getRecordNumberList().lastElement();
	}

	public int getCacheSize() {
		return getAccessTimeList().size();
	}

	class DataBlock extends Block {

	    public static final String VERSION = "$Id: DataStore.java,v 1.5 2005/08/20 21:21:21 amphibian Exp $";

		private static final short KEY_SIZE = Key.KEY_SIZE_ON_DISK;
		private static final short ACCESS_TIME_SIZE = 8;
		private static final short SIZE_ON_DISK = KEY_SIZE + ACCESS_TIME_SIZE;

		public DataBlock(int recordNum, Key key, long accessTime) {
			super(recordNum, key, accessTime);
		}

		public long positionInIndexFile() {
			/* key + 8 byte last access time */
			return getRecordNumber() * SIZE_ON_DISK;
		}

		public long positionInDataFile() {
			return getRecordNumber() * blockSize;
		}
	}
}

