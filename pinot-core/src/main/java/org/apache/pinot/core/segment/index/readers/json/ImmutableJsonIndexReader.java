/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.segment.index.readers.json;

import com.google.common.base.Preconditions;
import java.nio.ByteOrder;
import java.util.List;
import org.apache.pinot.core.query.request.context.ExpressionContext;
import org.apache.pinot.core.query.request.context.FilterContext;
import org.apache.pinot.core.query.request.context.predicate.EqPredicate;
import org.apache.pinot.core.query.request.context.predicate.InPredicate;
import org.apache.pinot.core.query.request.context.predicate.NotEqPredicate;
import org.apache.pinot.core.query.request.context.predicate.NotInPredicate;
import org.apache.pinot.core.query.request.context.predicate.Predicate;
import org.apache.pinot.core.segment.creator.impl.inv.json.BaseJsonIndexCreator;
import org.apache.pinot.core.segment.index.readers.BitmapInvertedIndexReader;
import org.apache.pinot.core.segment.index.readers.JsonIndexReader;
import org.apache.pinot.core.segment.index.readers.StringDictionary;
import org.apache.pinot.core.segment.memory.PinotDataBuffer;
import org.apache.pinot.spi.utils.JsonUtils;
import org.roaringbitmap.IntConsumer;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;


/**
 * Reader for json index.
 */
public class ImmutableJsonIndexReader implements JsonIndexReader {
  // NOTE: Use long type for _numDocs to comply with the RoaringBitmap APIs.
  private final long _numDocs;
  private final StringDictionary _dictionary;
  private final BitmapInvertedIndexReader _invertedIndex;
  private final PinotDataBuffer _docIdMapping;

  public ImmutableJsonIndexReader(PinotDataBuffer dataBuffer, int numDocs) {
    _numDocs = numDocs;

    int version = dataBuffer.getInt(0);
    Preconditions.checkState(version == BaseJsonIndexCreator.VERSION, "Unsupported json index version: %s", version);

    int maxValueLength = dataBuffer.getInt(4);
    long dictionaryLength = dataBuffer.getLong(8);
    long invertedIndexLength = dataBuffer.getLong(16);
    long docIdMappingLength = dataBuffer.getLong(24);

    long dictionaryStartOffset = BaseJsonIndexCreator.HEADER_LENGTH;
    long dictionaryEndOffset = dictionaryStartOffset + dictionaryLength;
    _dictionary =
        new StringDictionary(dataBuffer.view(dictionaryStartOffset, dictionaryEndOffset, ByteOrder.BIG_ENDIAN), 0,
            maxValueLength, (byte) 0);
    long invertedIndexEndOffset = dictionaryEndOffset + invertedIndexLength;
    _invertedIndex = new BitmapInvertedIndexReader(
        dataBuffer.view(dictionaryEndOffset, invertedIndexEndOffset, ByteOrder.BIG_ENDIAN), _dictionary.length());
    long docIdMappingEndOffset = invertedIndexEndOffset + docIdMappingLength;
    _docIdMapping = dataBuffer.view(invertedIndexEndOffset, docIdMappingEndOffset, ByteOrder.LITTLE_ENDIAN);
  }

  @Override
  public MutableRoaringBitmap getMatchingDocIds(FilterContext filter) {
    if (filter.getType() == FilterContext.Type.PREDICATE && isExclusive(filter.getPredicate().getType())) {
      // Handle exclusive predicate separately because the flip can only be applied to the unflattened doc ids in order
      // to get the correct result, and it cannot be nested
      MutableRoaringBitmap matchingFlattenedDocIds = getMatchingFlattenedDocIds(filter.getPredicate());
      MutableRoaringBitmap matchingDocIds = new MutableRoaringBitmap();
      matchingFlattenedDocIds.forEach((IntConsumer) flattenedDocId -> matchingDocIds.add(getDocId(flattenedDocId)));
      matchingDocIds.flip(0, _numDocs);
      return matchingDocIds;
    } else {
      MutableRoaringBitmap matchingFlattenedDocIds = getMatchingFlattenedDocIds(filter);
      MutableRoaringBitmap matchingDocIds = new MutableRoaringBitmap();
      matchingFlattenedDocIds.forEach((IntConsumer) flattenedDocId -> matchingDocIds.add(getDocId(flattenedDocId)));
      return matchingDocIds;
    }
  }

  /**
   * Returns {@code true} if the given predicate type is exclusive for json_match calculation, {@code false} otherwise.
   */
  private boolean isExclusive(Predicate.Type predicateType) {
    return predicateType == Predicate.Type.NOT_EQ || predicateType == Predicate.Type.NOT_IN
        || predicateType == Predicate.Type.IS_NULL;
  }

  /**
   * Returns the matching flattened doc ids for the given filter.
   */
  private MutableRoaringBitmap getMatchingFlattenedDocIds(FilterContext filter) {
    switch (filter.getType()) {
      case AND: {
        List<FilterContext> children = filter.getChildren();
        int numChildren = children.size();
        MutableRoaringBitmap matchingDocIds = getMatchingFlattenedDocIds(children.get(0));
        for (int i = 1; i < numChildren; i++) {
          matchingDocIds.and(getMatchingFlattenedDocIds(children.get(i)));
        }
        return matchingDocIds;
      }
      case OR: {
        List<FilterContext> children = filter.getChildren();
        int numChildren = children.size();
        MutableRoaringBitmap matchingDocIds = getMatchingFlattenedDocIds(children.get(0));
        for (int i = 1; i < numChildren; i++) {
          matchingDocIds.or(getMatchingFlattenedDocIds(children.get(i)));
        }
        return matchingDocIds;
      }
      case PREDICATE: {
        Predicate predicate = filter.getPredicate();
        Preconditions
            .checkState(!isExclusive(predicate.getType()), "Exclusive predicate: %s cannot be nested", predicate);
        return getMatchingFlattenedDocIds(predicate);
      }
      default:
        throw new IllegalStateException();
    }
  }

  /**
   * Returns the matching flattened doc ids for the given predicate.
   * <p>Exclusive predicate is handled as the inclusive predicate, and the caller should flip the unflattened doc ids in
   * order to get the correct exclusive predicate result.
   */
  private MutableRoaringBitmap getMatchingFlattenedDocIds(Predicate predicate) {
    ExpressionContext lhs = predicate.getLhs();
    Preconditions.checkState(lhs.getType() == ExpressionContext.Type.IDENTIFIER,
        "Left-hand side of the predicate must be an identifier, got: %s (%s). Put double quotes around the identifier if needed.",
        lhs, lhs.getType());
    String key = lhs.getIdentifier();

    // Process the array index within the key if exists
    // E.g. "foo[0].bar[1].foobar"='abc' -> foo.$index=0 && foo.bar.$index=1 && foo.bar.foobar='abc'
    MutableRoaringBitmap matchingDocIds = null;
    int leftBracketIndex;
    while ((leftBracketIndex = key.indexOf('[')) > 0) {
      int rightBracketIndex = key.indexOf(']');
      Preconditions.checkState(rightBracketIndex > leftBracketIndex, "Missing right bracket in key: %s", key);

      String leftPart = key.substring(0, leftBracketIndex);
      int arrayIndex;
      try {
        arrayIndex = Integer.parseInt(key.substring(leftBracketIndex + 1, rightBracketIndex));
      } catch (Exception e) {
        throw new IllegalStateException("Invalid key: " + key);
      }
      String rightPart = key.substring(rightBracketIndex + 1);

      // foo[1].bar -> foo.$index=1
      String searchKey =
          leftPart + JsonUtils.KEY_SEPARATOR + JsonUtils.ARRAY_INDEX_KEY + BaseJsonIndexCreator.KEY_VALUE_SEPARATOR
              + arrayIndex;
      int dictId = _dictionary.indexOf(searchKey);
      if (dictId >= 0) {
        ImmutableRoaringBitmap docIds = _invertedIndex.getDocIds(dictId);
        if (matchingDocIds == null) {
          matchingDocIds = docIds.toMutableRoaringBitmap();
        } else {
          matchingDocIds.and(docIds);
        }
        key = leftPart + rightPart;
      } else {
        return new MutableRoaringBitmap();
      }
    }

    Predicate.Type predicateType = predicate.getType();
    if (predicateType == Predicate.Type.EQ || predicateType == Predicate.Type.NOT_EQ) {
      String value = predicateType == Predicate.Type.EQ ? ((EqPredicate) predicate).getValue()
          : ((NotEqPredicate) predicate).getValue();
      String keyValuePair = key + BaseJsonIndexCreator.KEY_VALUE_SEPARATOR + value;
      int dictId = _dictionary.indexOf(keyValuePair);
      if (dictId >= 0) {
        ImmutableRoaringBitmap matchingDocIdsForKeyValuePair = _invertedIndex.getDocIds(dictId);
        if (matchingDocIds == null) {
          matchingDocIds = matchingDocIdsForKeyValuePair.toMutableRoaringBitmap();
        } else {
          matchingDocIds.and(matchingDocIdsForKeyValuePair);
        }
        return matchingDocIds;
      } else {
        return new MutableRoaringBitmap();
      }
    } else if (predicateType == Predicate.Type.IN || predicateType == Predicate.Type.NOT_IN) {
      List<String> values = predicateType == Predicate.Type.IN ? ((InPredicate) predicate).getValues()
          : ((NotInPredicate) predicate).getValues();
      MutableRoaringBitmap matchingDocIdsForKeyValuePairs = new MutableRoaringBitmap();
      for (String value : values) {
        String keyValuePair = key + BaseJsonIndexCreator.KEY_VALUE_SEPARATOR + value;
        int dictId = _dictionary.indexOf(keyValuePair);
        if (dictId >= 0) {
          matchingDocIdsForKeyValuePairs.or(_invertedIndex.getDocIds(dictId));
        }
      }
      if (matchingDocIds == null) {
        matchingDocIds = matchingDocIdsForKeyValuePairs;
      } else {
        matchingDocIds.and(matchingDocIdsForKeyValuePairs);
      }
      return matchingDocIds;
    } else if (predicateType == Predicate.Type.IS_NOT_NULL || predicateType == Predicate.Type.IS_NULL) {
      int dictId = _dictionary.indexOf(key);
      if (dictId >= 0) {
        ImmutableRoaringBitmap matchingDocIdsForKey = _invertedIndex.getDocIds(dictId);
        if (matchingDocIds == null) {
          matchingDocIds = matchingDocIdsForKey.toMutableRoaringBitmap();
        } else {
          matchingDocIds.and(matchingDocIdsForKey);
        }
        return matchingDocIds;
      } else {
        return new MutableRoaringBitmap();
      }
    } else {
      throw new IllegalStateException("Unsupported json_match predicate type: " + predicate);
    }
  }

  private int getDocId(int flattenedDocId) {
    return _docIdMapping.getInt((long) flattenedDocId << 2);
  }

  @Override
  public void close() {
    // NOTE: DO NOT close the PinotDataBuffer here because it is tracked by the caller and might be reused later. The
    // caller is responsible of closing the PinotDataBuffer.
  }
}
