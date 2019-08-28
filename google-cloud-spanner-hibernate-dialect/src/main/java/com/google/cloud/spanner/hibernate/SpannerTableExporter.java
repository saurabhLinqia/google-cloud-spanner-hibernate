/*
 * Copyright 2019 Google LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */

package com.google.cloud.spanner.hibernate;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.hibernate.boot.Metadata;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Index;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.Exporter;

/**
 * The exporter for Cloud Spanner CREATE and DROP table statements.
 * 
 * @author Chengyuan Zhao
 */
public class SpannerTableExporter implements Exporter<Table> {

    private final SpannerDialect spannerDialect;

    private final String createTableTemplate;

    /**
     * Constructor.
     * 
     * @param spannerDialect
     *            a Cloud Spanner dialect.
     */
    public SpannerTableExporter(SpannerDialect spannerDialect) {
        this.spannerDialect = spannerDialect;
        this.createTableTemplate = this.spannerDialect.getCreateTableString()
                + " {0} ({1}) PRIMARY KEY ({2})";
    }

    @Override
  public String[] getSqlCreateStrings(Table table, Metadata metadata) {
    /* The current implementation does not support UNIQUE constraints/indexes for relationships */

    Table containingTable = getContainingTableForCollection(metadata, table);

    Iterable<Column> keyColumns;

    if (table.hasPrimaryKey()) {
      // a typical table that corresponds to an entity type
      keyColumns = table.getPrimaryKey().getColumns();
    } else if (containingTable != null) {
      // a table that is actually an element collection property
      keyColumns = table::getColumnIterator;
    } else {
      // the case corresponding to a sequence-table that will only have 1 row.
      keyColumns = Collections.emptyList();
    }
    return getTableString(table, metadata, keyColumns);
  }

    private String[] getTableString(Table table, Metadata metadata, Iterable<Column> keyColumns) {

    String primaryKeyColNames = StreamSupport.stream(keyColumns.spliterator(), false)
        .map(Column::getQuotedName)
        .collect(Collectors.joining(","));

    StringJoiner colsAndTypes = new StringJoiner(",");

    ((Iterator<Column>) table.getColumnIterator()).forEachRemaining(col -> colsAndTypes
        .add(col.getQuotedName()
            + " " + col.getSqlType(this.spannerDialect, metadata)
            + (col.isNullable() ? this.spannerDialect.getNullColumnString() : " not null")));

    ArrayList<String> statements = new ArrayList<>();
    statements.add(MessageFormat.format(this.createTableTemplate, table.getQuotedName(),
        colsAndTypes.toString(), primaryKeyColNames));

    // Hibernate requires the special hibernate_sequence table to be populated with an initial val.
    if (table.getName().equals(SequenceStyleGenerator.DEF_SEQUENCE_NAME)) {
      statements.add("INSERT INTO " + SequenceStyleGenerator.DEF_SEQUENCE_NAME + " ("
          + SequenceStyleGenerator.DEF_VALUE_COLUMN + ") VALUES(1)");
    }

    return statements.toArray(new String[0]);
  }

    private Table getContainingTableForCollection(Metadata metadata,
            Table collectionTable) {
        for (Collection collection : metadata.getCollectionBindings()) {
            if (collection.getCollectionTable().equals(collectionTable)) {
                return collection.getTable();
            }
        }
        return null;
    }

    @Override
    public String[] getSqlDropStrings(Table table, Metadata metadata) {
        /*
         * Cloud Spanner requires examining the metadata to find all indexes and
         * interleaved tables. These must be dropped before the given table can
         * be dropped. The current implementation does not support interleaved
         * tables.
         */

        ArrayList<String> dropStrings = new ArrayList<>();

        Iterator<Index> iteratorIdx = table.getIndexIterator();
        while (iteratorIdx.hasNext()) {
            Index curr = iteratorIdx.next();
            dropStrings.add("drop index " + curr.getName());
        }

        dropStrings.add(this.spannerDialect.getDropTableString(table
                .getQuotedName()));

        return dropStrings.toArray(new String[0]);
    }
}
