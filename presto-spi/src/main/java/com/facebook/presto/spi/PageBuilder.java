/*
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
package com.facebook.presto.spi;

import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.PageBuilderStatus;
import com.facebook.presto.spi.type.FixedWidthType;
import com.facebook.presto.spi.type.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.facebook.presto.spi.block.BlockBuilderStatus.DEFAULT_MAX_BLOCK_SIZE_IN_BYTES;
import static com.facebook.presto.spi.block.PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

public class PageBuilder
{
    private final BlockBuilder[] blockBuilders;
    private final List<Type> types;
    private PageBuilderStatus pageBuilderStatus;
    private int declaredPositions;

    public PageBuilder(List<? extends Type> types)
    {
        this(Integer.MAX_VALUE, types);
    }

    public PageBuilder(int initialExpectedEntries, List<? extends Type> types)
    {
        this(initialExpectedEntries, DEFAULT_MAX_PAGE_SIZE_IN_BYTES, types, Optional.empty());
    }

    public static PageBuilder withMaxPageSize(int maxPageBytes, List<? extends Type> types)
    {
        return new PageBuilder(Integer.MAX_VALUE, maxPageBytes, types, Optional.empty());
    }

    private PageBuilder(int initialExpectedEntries, int maxPageBytes, List<? extends Type> types, Optional<BlockBuilder[]> templateBlockBuilders)
    {
        this.types = unmodifiableList(new ArrayList<>(requireNonNull(types, "types is null")));

        int maxBlockSizeInBytes;
        if (!types.isEmpty()) {
            maxBlockSizeInBytes = (int) (1.0 * maxPageBytes / types.size());
            maxBlockSizeInBytes = Math.min(DEFAULT_MAX_BLOCK_SIZE_IN_BYTES, maxBlockSizeInBytes);
        }
        else {
            maxBlockSizeInBytes = 0;
        }
        pageBuilderStatus = new PageBuilderStatus(maxPageBytes, maxBlockSizeInBytes);
        blockBuilders = new BlockBuilder[types.size()];

        if (templateBlockBuilders.isPresent()) {
            BlockBuilder[] templates = templateBlockBuilders.get();
            checkArgument(templates.length == types.size(), "Size of templates and types should match");
            for (int i = 0; i < blockBuilders.length; i++) {
                blockBuilders[i] = templates[i].newBlockBuilderLike(pageBuilderStatus.createBlockBuilderStatus());
            }
        }
        else {
            int expectedEntries = Math.min(maxBlockSizeInBytes, initialExpectedEntries);
            for (Type type : types) {
                if (type instanceof FixedWidthType) {
                    int fixedSize = Math.max(((FixedWidthType) type).getFixedSize(), 1);
                    expectedEntries = Math.min(expectedEntries, maxBlockSizeInBytes / fixedSize);
                }
                else {
                    // We really have no idea how big these are going to be, so just guess. In reset() we'll make a better guess
                    expectedEntries = Math.min(expectedEntries, maxBlockSizeInBytes / 32);
                }
            }
            for (int i = 0; i < blockBuilders.length; i++) {
                blockBuilders[i] = types.get(i).createBlockBuilder(
                        pageBuilderStatus.createBlockBuilderStatus(),
                        expectedEntries,
                        pageBuilderStatus.getMaxBlockSizeInBytes() / expectedEntries);
            }
        }
    }

    public void reset()
    {
        if (isEmpty()) {
            return;
        }
        pageBuilderStatus = new PageBuilderStatus(pageBuilderStatus.getMaxPageSizeInBytes(), pageBuilderStatus.getMaxBlockSizeInBytes());

        declaredPositions = 0;

        for (int i = 0; i < types.size(); i++) {
            blockBuilders[i] = blockBuilders[i].newBlockBuilderLike(pageBuilderStatus.createBlockBuilderStatus());
        }
    }

    public PageBuilder newPageBuilderLike()
    {
        return new PageBuilder(declaredPositions, pageBuilderStatus.getMaxPageSizeInBytes(), types, Optional.of(blockBuilders));
    }

    public BlockBuilder getBlockBuilder(int channel)
    {
        return blockBuilders[channel];
    }

    public Type getType(int channel)
    {
        return types.get(channel);
    }

    public void declarePosition()
    {
        declaredPositions++;
    }

    public void declarePositions(int positions)
    {
        declaredPositions += positions;
    }

    public boolean isFull()
    {
        return declaredPositions == Integer.MAX_VALUE || pageBuilderStatus.isFull();
    }

    public boolean isEmpty()
    {
        return declaredPositions == 0;
    }

    public int getPositionCount()
    {
        return declaredPositions;
    }

    public long getSizeInBytes()
    {
        return pageBuilderStatus.getSizeInBytes();
    }

    public long getRetainedSizeInBytes()
    {
        return Stream.of(blockBuilders).mapToLong(BlockBuilder::getRetainedSizeInBytes).sum();
    }

    public Page build()
    {
        if (blockBuilders.length == 0) {
            return new Page(declaredPositions);
        }

        Block[] blocks = new Block[blockBuilders.length];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = blockBuilders[i].build();
            if (blocks[i].getPositionCount() != declaredPositions) {
                throw new IllegalStateException(String.format("Declared positions (%s) does not match block %s's number of entries (%s)", declaredPositions, i, blocks[i].getPositionCount()));
            }
        }

        return new Page(blocks);
    }

    private static void checkArgument(boolean expression, String errorMessage)
    {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
