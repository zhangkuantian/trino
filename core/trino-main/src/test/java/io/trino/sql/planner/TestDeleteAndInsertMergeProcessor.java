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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slices;
import io.trino.operator.DeleteAndInsertMergeProcessor;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.ByteArrayBlock;
import io.trino.spi.block.IntArrayBlock;
import io.trino.spi.block.LongArrayBlock;
import io.trino.spi.block.PageBuilderStatus;
import io.trino.spi.block.RowBlock;
import io.trino.spi.block.SqlRow;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;

import static io.trino.spi.connector.ConnectorMergeSink.DELETE_OPERATION_NUMBER;
import static io.trino.spi.connector.ConnectorMergeSink.INSERT_OPERATION_NUMBER;
import static io.trino.spi.connector.ConnectorMergeSink.UPDATE_OPERATION_NUMBER;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDeleteAndInsertMergeProcessor
{
    @Test
    public void testSimpleDeletedRowMerge()
    {
        // target: ('Dave', 11, 'Devon'), ('Dave', 11, 'Darbyshire')
        // source: ('Dave', 11, 'Darbyshire')
        // merge:
        //     MERGE INTO target t USING source s
        //     ON t.customer = s.customer" +
        //     WHEN MATCHED AND t.address <> 'Darbyshire' AND s.purchases * 2 > 20" +
        //         THEN DELETE
        // expected: ('Dave', 11, 'Darbyshire')
        DeleteAndInsertMergeProcessor processor = makeMergeProcessor();
        Block[] rowIdBlocks = new Block[] {
                makeLongArrayBlock(1, 1), // TransactionId
                makeLongArrayBlock(1, 0), // rowId
                makeIntArrayBlock(536870912, 536870912)}; // bucket
        Block[] mergeCaseBlocks = new Block[] {
                makeVarcharArrayBlock(null, "Dave"), // customer
                new IntArrayBlock(2, Optional.of(new boolean[] {true, false}), new int[] {0, 11}), // purchases
                makeVarcharArrayBlock(null, "Devon"), // address
                new ByteArrayBlock(2, Optional.of(new boolean[] {true, false}), new byte[] {0, 1}), // "present" boolean
                new ByteArrayBlock(2, Optional.of(new boolean[] {true, false}), new byte[] {0, DELETE_OPERATION_NUMBER}), // "present" boolean
                new IntArrayBlock(2, Optional.of(new boolean[] {true, false}), new int[] {0, 0})
        };
        Page inputPage = new Page(
                RowBlock.fromNotNullSuppressedFieldBlocks(2, Optional.empty(), rowIdBlocks),
                RowBlock.fromNotNullSuppressedFieldBlocks(2, Optional.of(new boolean[] {true, false}), mergeCaseBlocks));

        Page outputPage = processor.transformPage(inputPage);
        assertThat(outputPage.getPositionCount()).isEqualTo(1);

        // The single operation is a delete
        assertThat((int) TINYINT.getByte(outputPage.getBlock(3), 0)).isEqualTo(DELETE_OPERATION_NUMBER);

        // Show that the row to be deleted is rowId 0, e.g. ('Dave', 11, 'Devon')
        SqlRow rowIdRow = ((RowBlock) outputPage.getBlock(5)).getRow(0);
        assertThat(BIGINT.getLong(rowIdRow.getRawFieldBlock(1), rowIdRow.getRawIndex())).isEqualTo(0);
    }

    @Test
    public void testUpdateAndDeletedMerge()
    {
        // target: ('Aaron', 11, 'Arches'), ('Bill', 7, 'Buena'), ('Dave', 11, 'Darbyshire'), ('Dave', 11, 'Devon'), ('Ed', 7, 'Etherville')
        // source: ('Aaron', 6, 'Arches'), ('Carol', 9, 'Centreville'), ('Dave', 11, 'Darbyshire'), ('Ed', 7, 'Etherville')
        // merge:
        //     MERGE INTO target t USING source s
        //    ON t.customer = s.customer" +
        //       WHEN MATCHED AND t.address <> 'Darbyshire' AND s.purchases * 2 > 20
        //           THEN DELETE" +
        //       WHEN MATCHED" +
        //           THEN UPDATE SET purchases = s.purchases + t.purchases, address = concat(t.address, '/', s.address)" +
        //       WHEN NOT MATCHED" +
        //           THEN INSERT (customer, purchases, address) VALUES(s.customer, s.purchases, s.address)
        // expected: ('Aaron', 17, 'Arches/Arches'), ('Bill', 7, 'Buena'), ('Carol', 9, 'Centreville'), ('Dave', 22, 'Darbyshire/Darbyshire'), ('Ed', 14, 'Etherville/Etherville'), ('Fred', 30, 'Franklin')
        DeleteAndInsertMergeProcessor processor = makeMergeProcessor();
        boolean[] rowIdNulls = new boolean[] {false, true, false, false, false};
        Page inputPage = makePageFromBlocks(
                5,
                Optional.of(rowIdNulls),
                new Block[] {
                        new LongArrayBlock(5, Optional.of(rowIdNulls), new long[] {2, 0, 1, 2, 2}), // TransactionId
                        new LongArrayBlock(5, Optional.of(rowIdNulls), new long[] {0, 0, 3, 1, 2}), // rowId
                        new IntArrayBlock(5, Optional.of(rowIdNulls), new int[] {536870912, 0, 536870912, 536870912, 536870912})}, // bucket
                new Block[] {
                        // customer
                        makeVarcharArrayBlock("Aaron", "Carol", "Dave", "Dave", "Ed"),
                        // purchases
                        makeIntArrayBlock(17, 9, 11, 22, 14),
                        // address
                        makeVarcharArrayBlock("Arches/Arches", "Centreville", "Devon", "Darbyshire/Darbyshire", "Etherville/Etherville"),
                        // "present" boolean
                        makeByteArrayBlock(1, 0, 1, 1, 1),
                        // operation number: update, insert, delete, update
                        makeByteArrayBlock(UPDATE_OPERATION_NUMBER, INSERT_OPERATION_NUMBER, DELETE_OPERATION_NUMBER, UPDATE_OPERATION_NUMBER, UPDATE_OPERATION_NUMBER),
                        makeIntArrayBlock(0, 1, 2, 0, 0)});

        Page outputPage = processor.transformPage(inputPage);
        assertThat(outputPage.getPositionCount()).isEqualTo(8);
        RowBlock rowIdBlock = (RowBlock) outputPage.getBlock(5);
        assertThat(rowIdBlock.getPositionCount()).isEqualTo(8);
        // Show that the first row has address "Arches"
        assertThat(getString(outputPage.getBlock(2), 1)).isEqualTo("Arches/Arches");
    }

    @Test
    public void testAnotherMergeCase()
    {
        /*
        inputPage: Page[positions=5
            0:Row[0:Long[2, 1, 2, 2], 1:Long[0, 3, 1, 2], 2:Int[536870912, 536870912, 536870912, 536870912]],
            1:Row[0:VarWidth["Aaron", "Carol", "Dave", "Dave", "Ed"], 1:Int[17, 9, 11, 22, 14], 2:VarWidth["Arches/Arches", "Centreville", "Devon", "Darbyshire/Darbyshir...", "Etherville/Ethervill..."], 3:Int[1, 2, 0, 1, 1], 4:Int[3, 1, 2, 3, 3]]]
Page[positions=8 0:Dict[VarWidth["Aaron", "Dave", "Dave", "Ed", "Aaron", "Carol", "Dave", "Ed"]], 1:Dict[Int[17, 11, 22, 14, 17, 9, 22, 14]], 2:Dict[VarWidth["Arches/Arches", "Devon", "Darbyshire/Darbyshir...", "Etherville/Ethervill...", "Arches/Arches", "Centreville", "Darbyshire/Darbyshir...", "Etherville/Ethervill..."]], 3:Int[2, 2, 2, 2, 1, 1, 1, 1], 4:Row[0:Dict[Long[2, 1, 2, 2, 2, 2, 2, 2]], 1:Dict[Long[0, 3, 1, 2, 0, 0, 0, 0]], 2:Dict[Int[536870912, 536870912, 536870912, 536870912, 536870912, 536870912, 536870912, 536870912]]]]
          Expected row count to be <5>, but was <7>; rows=[[Bill, 7, Buena], [Dave, 11, Devon], [Aaron, 11, Arches], [Aaron, 17, Arches/Arches], [Carol, 9, Centreville], [Dave, 22, Darbyshire/Darbyshire], [Ed, 14, Etherville/Etherville]]
         */
        DeleteAndInsertMergeProcessor processor = makeMergeProcessor();
        boolean[] rowIdNulls = new boolean[] {false, true, false, false, false};
        Page inputPage = makePageFromBlocks(
                5,
                Optional.of(rowIdNulls),
                new Block[] {
                        new LongArrayBlock(5, Optional.of(rowIdNulls), new long[] {2, 0, 1, 2, 2}), // TransactionId
                        new LongArrayBlock(5, Optional.of(rowIdNulls), new long[] {0, 0, 3, 1, 2}), // rowId
                        new IntArrayBlock(5, Optional.of(rowIdNulls), new int[] {536870912, 0, 536870912, 536870912, 536870912})}, // bucket
                new Block[] {
                        // customer
                        makeVarcharArrayBlock("Aaron", "Carol", "Dave", "Dave", "Ed"),
                        // purchases
                        makeIntArrayBlock(17, 9, 11, 22, 14),
                        // address
                        makeVarcharArrayBlock("Arches/Arches", "Centreville", "Devon", "Darbyshire/Darbyshire", "Etherville/Etherville"),
                        // "present" boolean
                        makeByteArrayBlock(1, 0, 1, 1, 0),
                        // operation number: update, insert, delete, update, update
                        makeByteArrayBlock(3, 1, 2, 3, 3),
                        makeIntArrayBlock(0, -1, 1, 0, 0)});

        Page outputPage = processor.transformPage(inputPage);
        assertThat(outputPage.getPositionCount()).isEqualTo(8);
        RowBlock rowIdBlock = (RowBlock) outputPage.getBlock(5);
        assertThat(rowIdBlock.getPositionCount()).isEqualTo(8);
        // Show that the first row has address "Arches/Arches"
        assertThat(getString(outputPage.getBlock(2), 1)).isEqualTo("Arches/Arches");
    }

    private static Page makePageFromBlocks(int positionCount, Optional<boolean[]> rowIdNulls, Block[] rowIdBlocks, Block[] mergeCaseBlocks)
    {
        Block[] pageBlocks = new Block[] {
                RowBlock.fromNotNullSuppressedFieldBlocks(positionCount, rowIdNulls, rowIdBlocks),
                RowBlock.fromFieldBlocks(positionCount, mergeCaseBlocks)
        };
        return new Page(pageBlocks);
    }

    private DeleteAndInsertMergeProcessor makeMergeProcessor()
    {
        // CREATE TABLE (customer VARCHAR, purchases INTEGER, address VARCHAR)
        List<Type> types = ImmutableList.of(VARCHAR, INTEGER, VARCHAR);

        RowType rowIdType = RowType.anonymous(ImmutableList.of(BIGINT, BIGINT, INTEGER));
        return new DeleteAndInsertMergeProcessor(types, rowIdType, 0, 1, ImmutableList.of(), ImmutableList.of(0, 1, 2));
    }

    private String getString(Block block, int position)
    {
        return VARBINARY.getSlice(block, position).toString(Charset.defaultCharset());
    }

    private LongArrayBlock makeLongArrayBlock(long... elements)
    {
        return new LongArrayBlock(elements.length, Optional.empty(), elements);
    }

    private IntArrayBlock makeIntArrayBlock(int... elements)
    {
        return new IntArrayBlock(elements.length, Optional.empty(), elements);
    }

    private ByteArrayBlock makeByteArrayBlock(int... elements)
    {
        byte[] bytes = new byte[elements.length];
        for (int index = 0; index < elements.length; index++) {
            bytes[index] = (byte) elements[index];
        }
        return new ByteArrayBlock(elements.length, Optional.empty(), bytes);
    }

    private Block makeVarcharArrayBlock(String... elements)
    {
        BlockBuilder builder = VARCHAR.createBlockBuilder(new PageBuilderStatus().createBlockBuilderStatus(), elements.length);
        for (String element : elements) {
            if (element == null) {
                builder.appendNull();
            }
            else {
                VARCHAR.writeSlice(builder, Slices.utf8Slice(element));
            }
        }
        return builder.build();
    }
}
