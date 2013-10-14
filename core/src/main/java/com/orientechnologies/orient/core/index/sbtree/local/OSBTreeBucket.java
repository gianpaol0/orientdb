/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientechnologies.orient.core.index.sbtree.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.index.sbtree.OTree;
import com.orientechnologies.orient.core.storage.impl.local.paginated.ODurablePage;

/**
 * @author Andrey Lomakin
 * @since 8/7/13
 */
public class OSBTreeBucket<K, V> extends ODurablePage {
  private static final int            MAX_ENTREE_SIZE         = OGlobalConfiguration.SBTREE_MAX_ENTREE_SIZE.getValueAsInteger();

  private static final int            FREE_POINTER_OFFSET     = WAL_POSITION_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int            SIZE_OFFSET             = FREE_POINTER_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int            IS_LEAF_OFFSET          = SIZE_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int            LEFT_SIBLING_OFFSET     = IS_LEAF_OFFSET + OByteSerializer.BYTE_SIZE;
  private static final int            RIGHT_SIBLING_OFFSET    = LEFT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int            TREE_SIZE_OFFSET        = RIGHT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int            KEY_SERIALIZER_OFFSET   = TREE_SIZE_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int            VALUE_SERIALIZER_OFFSET = KEY_SERIALIZER_OFFSET + OByteSerializer.BYTE_SIZE;

  private static final int            POSITIONS_ARRAY_OFFSET  = VALUE_SERIALIZER_OFFSET + OByteSerializer.BYTE_SIZE;

  public static final int             MAX_BUCKET_SIZE_BYTES   = OGlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;

  private final boolean               isLeaf;

  private final OBinarySerializer<K>  keySerializer;
  private final OBinarySerializer<V>  valueSerializer;

  private final Comparator<? super K> comparator              = ODefaultComparator.INSTANCE;

  public OSBTreeBucket(long cachePointer, boolean isLeaf, OBinarySerializer<K> keySerializer, OBinarySerializer<V> valueSerializer,
      TrackMode trackMode) throws IOException {
    super(cachePointer, trackMode);

    this.isLeaf = isLeaf;
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;

    setIntValue(FREE_POINTER_OFFSET, MAX_BUCKET_SIZE_BYTES);
    setIntValue(SIZE_OFFSET, 0);

    setByteValue(IS_LEAF_OFFSET, (byte) (isLeaf ? 1 : 0));
    setLongValue(LEFT_SIBLING_OFFSET, -1);
    setLongValue(RIGHT_SIBLING_OFFSET, -1);

    setLongValue(TREE_SIZE_OFFSET, 0);

    setByteValue(KEY_SERIALIZER_OFFSET, (byte) -1);
    setByteValue(VALUE_SERIALIZER_OFFSET, (byte) -1);
  }

  public OSBTreeBucket(long cachePointer, OBinarySerializer<K> keySerializer, OBinarySerializer<V> valueSerializer,
      TrackMode trackMode) {
    super(cachePointer, trackMode);

    this.isLeaf = getByteValue(IS_LEAF_OFFSET) > 0;
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
  }

  public byte getKeySerializerId() {
    return getByteValue(KEY_SERIALIZER_OFFSET);
  }

  public void setKeySerializerId(byte keySerializerId) {
    setByteValue(KEY_SERIALIZER_OFFSET, keySerializerId);
  }

  public byte getValueSerializerId() {
    return getByteValue(VALUE_SERIALIZER_OFFSET);
  }

  public void setValueSerializerId(byte valueSerializerId) {
    setByteValue(VALUE_SERIALIZER_OFFSET, valueSerializerId);
  }

  public void setTreeSize(long size) throws IOException {
    setLongValue(TREE_SIZE_OFFSET, size);
  }

  public long getTreeSize() {
    return getLongValue(TREE_SIZE_OFFSET);
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  public int find(K key) {
    int low = 0;
    int high = size() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      K midVal = getKey(mid);
      int cmp = comparator.compare(midVal, key);

      if (cmp < 0)
        low = mid + 1;
      else if (cmp > 0)
        high = mid - 1;
      else
        return mid; // key found
    }
    return -(low + 1); // key not found.
  }

  public void remove(int entryIndex) throws IOException {
    int entryPosition = getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);

    int entrySize = keySerializer.getObjectSizeInDirectMemory(directMemory, pagePointer + entryPosition);
    if (isLeaf) {
      if (valueSerializer.isFixedLength())
        entrySize += valueSerializer.getFixedLength();
      else
        entrySize += valueSerializer.getObjectSizeInDirectMemory(directMemory, pagePointer + entryPosition + entrySize);
    } else {
      throw new IllegalStateException("Remove is applies to leaf buckets only");
    }

    int size = size();
    if (entryIndex < size - 1) {
      copyData(POSITIONS_ARRAY_OFFSET + (entryIndex + 1) * OIntegerSerializer.INT_SIZE, POSITIONS_ARRAY_OFFSET + entryIndex
          * OIntegerSerializer.INT_SIZE, (size - entryIndex - 1) * OIntegerSerializer.INT_SIZE);
    }

    size--;
    setIntValue(SIZE_OFFSET, size);

    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (size > 0 && entryPosition > freePointer) {
      copyData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }
    setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

    int currentPositionOffset = POSITIONS_ARRAY_OFFSET;

    for (int i = 0; i < size; i++) {
      int currentEntryPosition = getIntValue(currentPositionOffset);
      if (currentEntryPosition < entryPosition)
        setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
      currentPositionOffset += OIntegerSerializer.INT_SIZE;
    }
  }

  public int size() {
    return getIntValue(SIZE_OFFSET);
  }

  public SBTreeEntry<K, V> getEntry(int entryIndex) {
    int entryPosition = getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (isLeaf) {
      K key = keySerializer.deserializeFromDirectMemory(directMemory, pagePointer + entryPosition);
      entryPosition += keySerializer.getObjectSizeInDirectMemory(directMemory, pagePointer + entryPosition);

      V value = valueSerializer.deserializeFromDirectMemory(directMemory, pagePointer + entryPosition);

      return new SBTreeEntry<K, V>(-1, -1, key, value);
    } else {
      long leftChild = getLongValue(entryPosition);
      entryPosition += OLongSerializer.LONG_SIZE;

      long rightChild = getLongValue(entryPosition);
      entryPosition += OLongSerializer.LONG_SIZE;

      K key = keySerializer.deserializeFromDirectMemory(directMemory, pagePointer + entryPosition);

      return new SBTreeEntry<K, V>(leftChild, rightChild, key, null);
    }
  }

  public K getKey(int index) {
    int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf)
      entryPosition += 2 * OLongSerializer.LONG_SIZE;

    return keySerializer.deserializeFromDirectMemory(directMemory, pagePointer + entryPosition);
  }

  public boolean isLeaf() {
    return isLeaf;
  }

  public void addAll(List<SBTreeEntry<K, V>> entries) throws IOException {
    for (int i = 0; i < entries.size(); i++)
      addEntry(i, entries.get(i), false);
  }

  public void shrink(int newSize) throws IOException {
    List<SBTreeEntry<K, V>> treeEntries = new ArrayList<SBTreeEntry<K, V>>(newSize);

    for (int i = 0; i < newSize; i++) {
      treeEntries.add(getEntry(i));
    }

    setIntValue(FREE_POINTER_OFFSET, MAX_BUCKET_SIZE_BYTES);
    setIntValue(SIZE_OFFSET, 0);

    int index = 0;
    for (SBTreeEntry<K, V> entry : treeEntries) {
      addEntry(index, entry, false);
      index++;
    }
  }

  public boolean addEntry(int index, SBTreeEntry<K, V> treeEntry, boolean updateNeighbors) throws IOException {
    final int keySize = keySerializer.getObjectSize(treeEntry.key);
    int valueSize = 0;
    int entrySize = keySize;

    if (isLeaf) {
      if (valueSerializer.isFixedLength())
        valueSize = valueSerializer.getFixedLength();
      else
        valueSize = valueSerializer.getObjectSize(treeEntry.value);

      entrySize += valueSize;

      checkEntreeSize(entrySize);
    } else
      entrySize += 2 * OLongSerializer.LONG_SIZE;

    int size = size();
    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize < (size + 1) * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET)
      return false;

    if (index <= size - 1) {
      copyData(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, POSITIONS_ARRAY_OFFSET + (index + 1)
          * OIntegerSerializer.INT_SIZE, (size - index) * OIntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    if (isLeaf) {
      byte[] serializedKey = new byte[keySize];
      keySerializer.serializeNative(treeEntry.key, serializedKey, 0);

      setBinaryValue(freePointer, serializedKey);
      freePointer += keySize;

      byte[] serializedValue = new byte[valueSize];
      valueSerializer.serializeNative(treeEntry.value, serializedValue, 0);
      setBinaryValue(freePointer, serializedValue);

    } else {
      setLongValue(freePointer, treeEntry.leftChild);
      freePointer += OLongSerializer.LONG_SIZE;

      setLongValue(freePointer, treeEntry.rightChild);
      freePointer += OLongSerializer.LONG_SIZE;

      byte[] serializedKey = new byte[keySize];
      keySerializer.serializeNative(treeEntry.key, serializedKey, 0);
      setBinaryValue(freePointer, serializedKey);

      size++;

      if (updateNeighbors && size > 1) {
        if (index < size - 1) {
          final int nextEntryPosition = getIntValue(POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE);
          setLongValue(nextEntryPosition, treeEntry.rightChild);
        }

        if (index > 0) {
          final int prevEntryPosition = getIntValue(POSITIONS_ARRAY_OFFSET + (index - 1) * OIntegerSerializer.INT_SIZE);
          setLongValue(prevEntryPosition + OLongSerializer.LONG_SIZE, treeEntry.leftChild);
        }
      }
    }

    return true;
  }

  public boolean updateValue(int index, V value) throws IOException {
    if (valueSerializer.isFixedLength()) {
      int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

      entryPosition += keySerializer.getObjectSizeInDirectMemory(directMemory, pagePointer + entryPosition);

      byte[] serializedValue = new byte[valueSerializer.getFixedLength()];
      valueSerializer.serializeNative(value, serializedValue, 0);

      setBinaryValue(entryPosition, serializedValue);
      return true;
    }

    final int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    int entreeSize = keySerializer.getObjectSizeInDirectMemory(directMemory, pagePointer + entryPosition);
    entreeSize += valueSerializer.getObjectSize(value);

    checkEntreeSize(entreeSize);

    final K key = getKey(index);
    remove(index);
    return addEntry(index, new SBTreeEntry<K, V>(-1, -1, key, value), false);
  }

  private void checkEntreeSize(int entreeSize) {
    if (entreeSize > MAX_ENTREE_SIZE)
      throw new OSBTreeException("Serialized key-value pair size bigger than allowed " + entreeSize + " vs " + MAX_ENTREE_SIZE
          + ".");
  }

  public void setLeftSibling(long pageIndex) throws IOException {
    setLongValue(LEFT_SIBLING_OFFSET, pageIndex);
  }

  public long getLeftSibling() {
    return getLongValue(LEFT_SIBLING_OFFSET);
  }

  public void setRightSibling(long pageIndex) throws IOException {
    setLongValue(RIGHT_SIBLING_OFFSET, pageIndex);
  }

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  public static final class SBTreeEntry<K, V> implements OTree.BucketEntry<K, V>, Comparable<SBTreeEntry<K, V>> {
    private final Comparator<? super K> comparator = ODefaultComparator.INSTANCE;

    public final long                   leftChild;
    public final long                   rightChild;
    public final K                      key;
    public final V                      value;

    public SBTreeEntry(long leftChild, long rightChild, K key, V value) {
      this.leftChild = leftChild;
      this.rightChild = rightChild;
      this.key = key;
      this.value = value;
    }

    public K getKey() {
      return key;
    }

    public V getValue() {
      return value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;

      SBTreeEntry that = (SBTreeEntry) o;

      if (leftChild != that.leftChild)
        return false;
      if (rightChild != that.rightChild)
        return false;
      if (!key.equals(that.key))
        return false;
      if (value != null ? !value.equals(that.value) : that.value != null)
        return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = (int) (leftChild ^ (leftChild >>> 32));
      result = 31 * result + (int) (rightChild ^ (rightChild >>> 32));
      result = 31 * result + key.hashCode();
      result = 31 * result + (value != null ? value.hashCode() : 0);
      return result;
    }

    @Override
    public String toString() {
      return "SBTreeEntry{" + "leftChild=" + leftChild + ", rightChild=" + rightChild + ", key=" + key + ", value=" + value + '}';
    }

    @Override
    public int compareTo(SBTreeEntry<K, V> other) {
      return comparator.compare(key, other.key);
    }
  }
}
