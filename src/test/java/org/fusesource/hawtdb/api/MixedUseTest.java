/**
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.hawtdb.api;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.codec.IntegerCodec;
import org.fusesource.hawtbuf.codec.LongCodec;
import org.fusesource.hawtdb.api.Paged.SliceType;
import org.junit.Test;

/**
 * Tests the use of interleaved custom data pages and index pages.
 * 
 */
public class MixedUseTest {
    public static final Buffer MAGIC = new Buffer(new byte[]{'c'});
    public static short PAGE_SIZE = 1024 * 29;

    private final int nObjects = 70000;
    
    private TxPageFileFactory m_factory;
    private TxPageFile m_pageFile;
    private IndexFactory<Long, Integer> m_indexFactory;
    private short m_pageSize;
    private short m_dataSize;
    
    private static IndexFactory<Long, Integer> createBTreeIndex(boolean deferred) {
        BTreeIndexFactory<Long, Integer> indexFactory = new BTreeIndexFactory<Long, Integer>();
        indexFactory.setDeferredEncoding(deferred);
        indexFactory.setKeyCodec(LongCodec.INSTANCE);
        indexFactory.setValueCodec(IntegerCodec.INSTANCE);
        return indexFactory;
    }

    private static IndexFactory<Long, Integer> createHashIndex(boolean deferred) {
        HashIndexFactory<Long, Integer> indexFactory = new HashIndexFactory<Long, Integer>();
        indexFactory.setDeferredEncoding(deferred);
        indexFactory.setKeyCodec(LongCodec.INSTANCE);
        indexFactory.setValueCodec(IntegerCodec.INSTANCE);
        return indexFactory;
    }

    private TxPageFile createFileFactory(File path, short pageSize) throws IOException {
    	m_factory = new TxPageFileFactory();
        m_factory.setFile(path);
        m_factory.setPageSize(pageSize);
        m_factory.setSync(false);
        m_factory.open();
        return m_factory.getTxPageFile();
    }
    
    public void setUp(short pageSize, IndexFactory<Long, Integer> indexType) throws IOException {
    	File path = File.createTempFile("test-mixed", ".tmp", new File("test"));
        path.delete();
    	System.out.println("file: " + path);
    	
        m_pageSize = pageSize;
        m_dataSize = (short) (pageSize - (MAGIC.length + Long.SIZE / 8));
        m_pageFile = createFileFactory(path, pageSize);
        m_indexFactory = indexType;

        // create the index
        Transaction tx = m_pageFile.tx();
        try {
            Index<Long, Integer> index = m_indexFactory.create(tx);

            // seed the index with some metadata about the test
            index.put(-2L, (int) m_pageSize);
            index.put(-3L, (int) m_dataSize);
            index.put(-4L, MAGIC.length);
        }
        finally {
            tx.commit();
        }

        m_pageFile.flush();
    }

	private void takeDown() {
		try {
			m_factory.close();
		}
		catch (Exception ex) {
			System.out.println("unable to close");
			ex.printStackTrace(System.out);
		}
		
		
		if (m_factory != null) {
			try { 
				m_factory.forceClose();
			}
			catch (Exception ex) {
				System.out.println("unable to close");
				ex.printStackTrace(System.out);
			}
		}
	}

    private class CustomObject {
        private long id;
        private byte[] data;

        public CustomObject(long id) {
            this.id = id;
            this.data = new byte[m_dataSize];
        }
    }


    public CustomObject load(Paged p, int page) {
        ByteBuffer bb = p.slice(SliceType.READ, page, 1);
        try {
            // check the magic
            Buffer magicBuffer = new Buffer(MAGIC.length);
            bb.get(magicBuffer.data);
            if (!MAGIC.equals(magicBuffer))
                throw new IllegalArgumentException("unknown page type");

            CustomObject co = new CustomObject(bb.getLong());
            bb.get(co.data);
            return co;
        }
        finally {
            p.unslice(bb);
        }
    }

    public List<Integer> store(Paged p, int page, CustomObject value) {
        ByteBuffer bb = p.slice(SliceType.WRITE, page, 1);
        try {
            bb.put(MAGIC.data);
            bb.putLong(value.id);
            bb.put(value.data);
        }
        finally {
            p.unslice(bb);
        }
        return Collections.EMPTY_LIST;
    }


    private void store(CustomObject c) {
        Transaction tx = m_pageFile.tx();
        try {
            Index<Long, Integer> idx = m_indexFactory.open(tx);
            Integer pageNumber = idx.get(c.id);
            // see if the index contains this object
            if (pageNumber == null) {
                pageNumber = tx.alloc();
                store(tx, pageNumber, c);
                idx.put(c.id, pageNumber);
            } else {
                store(tx, pageNumber, c);
            }
            tx.commit();
        }
        finally {
            tx.close();
        }
    }
    
    private CustomObject load( long id ) {
        Transaction tx = m_pageFile.tx();
        try {
            Index<Long, Integer> idx = m_indexFactory.open(tx);
            Integer pageNumber = idx.get(id);
            // see if the index contains this object
            if (pageNumber == null) {
                return null;
            } else {
                return load(tx, pageNumber);
            }
        } finally {
            tx.commit();
            tx.close();
        }
    }

    private void createObjects() throws Exception {
        System.out.print("creating: ");
        
        try {
	        for (int i = 0; i < nObjects; i++) {
	        	try {
	        		if (i % (nObjects/20) == 0) {
	        			System.out.print(i + " "); System.out.flush(); 
	        		}
	        		
		            CustomObject c = new CustomObject(i);
		            store(c);
	        	}
	        	catch (Exception e) {
	        		System.out.println("\nunable to create: " + i);
	        		throw e;
	        	}
	        }
        }
        finally {
        	System.out.println();
        }
    }
    
    private void createObjectsTest() throws Exception {
    	try {
    		createObjects();
    	}
    	finally {
    		takeDown();
    	}
    }
    
    @Test
    public void testNoIndex() throws Exception {
        setUp(PAGE_SIZE, new HashMapIndexFactory());
        createObjectsTest();
    }

    @Test
    public void testDeferredHashIndex() throws Exception {
        setUp(PAGE_SIZE, createHashIndex(true));
        createObjectsTest();
    }

    @Test
    public void testHashIndex() throws Exception {
        setUp(PAGE_SIZE, createHashIndex(false));
        createObjectsTest();
    }

    @Test
    public void testDeferredBTreeIndex() throws Exception {
        setUp(PAGE_SIZE, createBTreeIndex(true));
        createObjectsTest();
    }
    
    /**
     * note: This takes a LONG time to finish.
     * @throws Exception
     */
    @Test
    public void testBTreeIndex() throws Exception {
        setUp(PAGE_SIZE , createBTreeIndex(false));
        createObjectsTest();
    }
    
}

// mock in-memory index and factory via 
class HashMapIndexFactory implements IndexFactory<Long, Integer>, Index<Long, Integer> {
	private HashMap<Long, Integer> m_map = new HashMap<Long, Integer>();
	
	// factory methods
	public Index<Long, Integer> create(Paged paged) {
		return this;
	}
	public Index<Long, Integer> open(Paged paged, int indexNumber) {
		return this;
	}
	public Index<Long, Integer> open(Paged paged) {
		return this;
	}
	
	// index methods
	public void clear() {
		m_map.clear();
	}
	public boolean containsKey(Long key) {
		return m_map.containsKey(key);
	}
	public void destroy() {
		m_map.clear();
	}
	public Integer get(Long key) {
		return m_map.get(key);
	}
	public int getIndexLocation() {
		return 0;
	}
	public boolean isEmpty() {
		return m_map.size() == 0;
	}
	public Integer put(Long key, Integer entry) {
		return m_map.put(key, entry);
	}
	public Integer putIfAbsent(Long key, Integer entry) {
		if (!m_map.containsKey(key))
			return m_map.put(key, entry);
		return null;
	}
	public Integer remove(Long key) {
		return m_map.remove(key);
	}
	public int size() {
		return m_map.size();
	}
}
