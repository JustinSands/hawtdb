/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.hawtdb.internal.index;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;

import org.fusesource.hawtdb.api.TxPageFile;
import org.fusesource.hawtdb.api.TxPageFileFactory;
import org.fusesource.hawtdb.api.Index;
import org.fusesource.hawtdb.api.Transaction;
import org.junit.After;
import org.junit.Test;


/**
 * Tests an Index
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public abstract class IndexTestSupport {
    
    private TxPageFileFactory pff;
    protected TxPageFile pf;
    protected Index<String,Long> index;
    protected Transaction tx;

    
    protected TxPageFileFactory createConcurrentPageFileFactory() {
        TxPageFileFactory rc = new TxPageFileFactory();
        rc.setFile(new File("target/test-data/" + getClass().getName() + ".db"));
        return rc;
    }
    
    @After
    public void tearDown() throws Exception {
        if( pf!=null ) {
            pff.close();
            pff = null;
        }
    }
    
    abstract protected Index<String,Long> createIndex(int page);

    private static final int COUNT = 10000;
    
    public void createPageFileAndIndex(short pageSize) throws Exception {
        pff = createConcurrentPageFileFactory();
        pff.setPageSize(pageSize);
        pff.getFile().delete();
        pff.open();
        pf = pff.getTxPageFile();
        tx = pf.tx();
        index = createIndex(-1);
        
    }

    protected void reloadAll() {
        int page = index.getIndexLocation();
        pff.close();
        pff.open();
        pf = pff.getTxPageFile();
        tx = pf.tx();
        index = createIndex(page);
    }
    
    protected void reloadIndex() {
        int page = index.getIndexLocation();
        tx.commit();
        index = createIndex(page);
    }

    @Test
    public void testIndexOperations() throws Exception {
        createPageFileAndIndex((short) 500);
        reloadIndex();
        doInsert(COUNT);
        reloadIndex();
        checkRetrieve(COUNT);
        doRemove(COUNT);
        reloadIndex();
        doInsert(COUNT);
        doRemoveHalf(COUNT);
        doInsertHalf(COUNT);
        reloadIndex();
        checkRetrieve(COUNT);
        doPutIfAbsent();
    }

    void doInsert(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            index.put(key(i), (long)i);
        }
        tx.commit();
    }

    protected String key(int i) {
        return "key:"+i;
    }

    void checkRetrieve(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            Long item = index.get(key(i));
            assertNotNull("Key missing: "+key(i), item);
        }
    }

    void doRemoveHalf(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                assertNotNull("Expected remove to return value for index "+i, index.remove(key(i)));
            }
        }
        tx.commit();
    }

    void doInsertHalf(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                index.put(key(i), (long)i);
            }
        }
        tx.commit();
    }

    void doRemove(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            assertNotNull("Expected remove to return value for index "+i, index.remove(key(i)));
        }
        tx.commit();
        for (int i = 0; i < count; i++) {
            Long item = index.get(key(i));
            assertNull(item);
        }
    }

    void doRemoveBackwards(int count) throws Exception {
        for (int i = count - 1; i >= 0; i--) {
            index.remove(key(i));
        }
        tx.commit();
        for (int i = 0; i < count; i++) {
            Long item = index.get(key(i));
            assertNull(item);
        }
    }

    void doPutIfAbsent() throws Exception {
        index.put("myKey", 0L);
        // Do not put on existent key:
        assertEquals((Long) 0L, index.putIfAbsent("myKey", 1L));
        assertEquals((Long) 0L, index.get("myKey"));
        // Put on absent key:
        assertEquals(null, index.putIfAbsent("absent", 1L));
        assertEquals((Long) 1L, index.get("absent"));
        tx.commit();

    }
}
