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
package org.apache.pinot.core.operator.blocks;

import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.pinot.common.request.transform.TransformExpressionTree;
import org.apache.pinot.core.common.BlockDocIdSet;
import org.apache.pinot.core.common.BlockDocIdValueSet;
import org.apache.pinot.core.common.BlockMetadata;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.common.DataBlock;
import org.apache.pinot.core.operator.docvalsets.TransformBlockValSet;
import org.apache.pinot.core.operator.transform.function.TransformFunction;


/**
 * Transform Block holds blocks of transformed columns.
 * <p>In absence of transforms, it servers as a pass-through to projection block.
 */
public class TransformBlock implements DataBlock {
  private final ProjectionBlock _projectionBlock;
  private final Map<String, TransformFunction> _transformFunctionMap;

  public TransformBlock(@Nonnull ProjectionBlock projectionBlock,
      @Nonnull Map<String, TransformFunction> transformFunctionMap) {
    _projectionBlock = projectionBlock;
    _transformFunctionMap = transformFunctionMap;
  }

  @Override
  public int getNumDocs() {
    return _projectionBlock.getNumDocs();
  }

  public BlockValSet getBlockValueSet(TransformExpressionTree expression) {
    if (expression.isColumn()) {
      return _projectionBlock.getBlockValueSet(expression.getValue());
    } else {
      return new TransformBlockValSet(_projectionBlock, _transformFunctionMap.get(expression));
    }
  }

  @Override
  public BlockValSet getBlockValueSet(String expression) {
    return new TransformBlockValSet(_projectionBlock, _transformFunctionMap.get(expression));
  }

  @Override
  public BlockDocIdSet getBlockDocIdSet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockValSet getBlockValueSet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockDocIdValueSet getBlockDocIdValueSet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlockMetadata getMetadata() {
    throw new UnsupportedOperationException();
  }

  public DocIdSetBlock getDocIdSetBlock(){
    return _projectionBlock.getDocIdSetBlock();
  }
}
