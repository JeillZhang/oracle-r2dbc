/*
  Copyright (c) 2020, 2021, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License 
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package oracle.r2dbc.util;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.ValidationDepth;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static oracle.r2dbc.DatabaseConfig.user;

/**
 * <p>
 * A {@link ConnectionFactory} which caches a single connection that is
 * shared by each {@code Publisher} returned by {@link #create()}. This
 * factory is used by test cases in order to eliminate the latency of opening a
 * new database connection. Rather than open a new connection for each test,
 * many tests can instead share a single connection.
 * </p><p>
 * This class implements a queue in which callers of {@link #create()} wait
 * to borrow the shared connection. A call to {@code create()} returns a
 * {@link Publisher} that emits the borrowed connection after the previous
 * borrower has returned it. A borrowed connection is returned when it's
 * {@link Connection#close()} method is called.
 * </p>
 */
public class SharedConnectionFactory implements ConnectionFactory {

  /** Metadata of the factory which created the shared connection */
  private final ConnectionFactoryMetadata metadata;

  /**
   * The tail of the borrowers queue. The current borrower completes the
   * current tail when {@link Connection#close()} is invoked on the borrowed
   * connection.
   */
  private final AtomicReference<CompletionStage<Connection>> borrowQueueTail;

  /**
   * Constructs a new connection factory that caches the connection emitted
   * by the {@code connectionPublisher}. The shared connection is emitted by the
   * publisher returned from {@link #create()}.
   *
   * @param connectionPublisher
   * @param metadata
   */
  public SharedConnectionFactory(
    Publisher<? extends Connection> connectionPublisher,
    ConnectionFactoryMetadata metadata) {
    CompletableFuture<Connection> initialTail = new CompletableFuture<>();
    Mono.from(connectionPublisher)
      .doOnNext(initialTail::complete)
      .doOnError(initialTail::completeExceptionally)
      .subscribe();

    borrowQueueTail = new AtomicReference<>(initialTail);
    this.metadata = metadata;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Borrows the shared connection. The returned publisher emits the
   * borrowed {@code Connection} once the the previous borrower has returned it.
   * </p><p>
   * The caller of this method claims the current the tail of the borrowers
   * queue, and leaves a new tail that the next borrower can claim. The caller
   * attaches a callback to their claimed tail. The caller's callback is invoked
   * when the previous borrower returns the connection.
   * </p><p>
   * Invocation of the caller's callback has the returned publisher emit the
   * connection, which is wrapped as a {@link BorrowedConnection}. When
   * {@link BorrowedConnection#close()} will invoke the callback attached to
   * the new tail that the next borrower claims.
   * </p><p>
   * This method can safely be called by multiple threads in parallel.
   * </p>
   * @return
   */
  @Override
  public Publisher<? extends Connection> create() {
    CompletableFuture<Connection> nextTail = new CompletableFuture<>();
    CompletionStage<Connection> currentTail =
      borrowQueueTail.getAndSet(nextTail);

    return Mono.fromCompletionStage(
      currentTail.thenApply(connection ->
        new BorrowedConnection(connection, nextTail)));
  }

  @Override
  public ConnectionFactoryMetadata getMetadata() {
    return metadata;
  }

  /**
   * <p>
   * Set to {@code true} to verify that all cursors have been closed when a
   * borrowed connection is returned.
   * </p><p>
   * A system property provides a hook to disable verification if the database
   * user can't have access to v$open_cursor, causing
   * {@link #queryOpenCursors(Connection)} to result in ORA-00942.
   * </p>
   */
  private static final Boolean IS_CURSOR_CLOSE_VERIFIED =
    ! Boolean.getBoolean("oracle.r2bdc.disableCursorCloseVerification");

  /**
   * <p>
   * Query that returns the SQL text of cursors that have not been closed.
   * </p><p>
   * The query doesn't return cursors for SQL beginning with "table_", because
   * Oracle R2DBC can not close these cursors. These cursors seem to be
   * implicitly created by database for stuff like LOB access, and can only
   * be closed by the database.
   * https://asktom.oracle.com/pls/apex/asktom.search?tag=vopen-cursorsql-text-showing-table-4-200-5e14-0-0-0#3332376126187r
   * </p><p>
   * This query also doesn't return the cursor for itself. This {@code String}
   * field should be set as the bind value for the :this_query parameter in
   * order to filter this query from querying itself.
   * </p>
   */
  private static final String OPEN_CURSOR_QUERY =
    "SELECT sql_text" +
      " FROM v$open_cursor" +
      " WHERE sid = userenv('sid')" +
      " AND cursor_type = 'OPEN'" +
      " AND sql_text NOT LIKE 'table_%'" +
      " AND sql_text <> substr(:this_query, 1, 60)" +
      " ORDER BY last_sql_active_time";

  /**
   * Returns a {@code Publisher} emitting the SQL Language text of all cursors
   * currently opened by a {@code connection}.
   * @param connection Database connection
   * @return Publisher of SQL for open cursors, which may be an empty stream
   * if there are no cursors open.
   */
  private static Publisher<String> queryOpenCursors(Connection connection) {

    if (! IS_CURSOR_CLOSE_VERIFIED)
      return Mono.empty();

    return Mono.from(connection.createStatement(OPEN_CURSOR_QUERY)
      .bind("this_query", OPEN_CURSOR_QUERY)
      .execute())
      .flatMapMany(result ->
        result.map((row, metadata) ->
          row.get("sql_text", String.class)))
      .onErrorMap(R2dbcException.class, r2dbcException ->
        // Handle ORA-00942
        r2dbcException.getErrorCode() == 942
          ? new RuntimeException(
              "V$OPEN_CUROSR is not accessible to the test user. " +
                "Grant access as SYSDBA with: " +
                "\"GRANT SELECT ON v_$open_cursor TO "+user()+"\", " +
                "or disable open cursor checks with: " +
                " -Doracle.r2bdc.disableCursorCloseVerification=true")
        : r2dbcException);
  }

  /**
   * Wraps a {@link Connection} in order to intercept calls to
   * {@link Connection#close()}. A call to {@code close()} will reset
   * the {@link #connection} to the original state it was in when
   * {@link ConnectionFactory#create()} created it. After the state is reset,
   * the {@link #returnedFuture} is completed to indicate that the connection is
   * ready for the next borrower to use.
   */
  private static final class BorrowedConnection implements Connection {

    /** Borrowed connection that this connection delegates to */
    private final Connection connection;

    /** Future completed when the borrowed connection is returned */
    private final CompletableFuture<Connection> returnedFuture;

    /** Set to true when this wrapper is closed */
    private volatile boolean isClosed = false;

    /**
     * The isolation level of the connection when it was borrowed. When the
     * borrowed connection is returned, it will be set back to this level.
     */
    private final IsolationLevel initialIsolationLevel;

    private BorrowedConnection(
      Connection connection,
      CompletableFuture<Connection> returnedFuture) {
      this.connection = connection;
      this.returnedFuture = returnedFuture;
      this.initialIsolationLevel = connection.getTransactionIsolationLevel();
    }

    /**
     * <p>
     * Resets the connection to the original state it was in when borrowed
     * and then completes the {@link #returnedFuture} to indicate that another
     * borrower may use the connection.
     * </p><p>
     * This method will also verify that no cursors have been left open. The
     * returned {@code Publisher} emits {@code onError} with an
     * {@link AssertionError} if one or more cursors have not been closed.
     * </p>
     */
    @Override
    public Publisher<Void> close() {

      // No-op if already closed
      if (isClosed)
        return Mono.empty();
      else
        isClosed = true;

      return Flux.defer(()-> {
          // Complete the returned future after cleaning up any transaction state
          if (connection.isAutoCommit()) {
            returnedFuture.complete(connection);
            return Mono.empty();
          }
          else {
            return Mono.from(connection.rollbackTransaction())
              .concatWith(connection.setAutoCommit(true))
              .concatWith(connection.setTransactionIsolationLevel(
                initialIsolationLevel))
              .doOnTerminate(() -> returnedFuture.complete(connection));
          }
        })
        .thenMany(queryOpenCursors(connection))
        .reduce(new LinkedList<String>(), (openCursorSqls, sql) -> {
          openCursorSqls.add(sql);
          return openCursorSqls;
        })
        .flatMap(openCursorSqls ->
          openCursorSqls.isEmpty() || openCursorSqls.get(0).equals("0")
            ? Mono.empty()
            : Mono.error(new AssertionError(
                "Cursors for the following SQL were not closed:\n" +
                String.join("\n", openCursorSqls))));

    }

    @Override
    public Publisher<Void> beginTransaction() {
      requireNotClosed();
      return connection.beginTransaction();
    }

    @Override
    public Publisher<Void> commitTransaction() {
      requireNotClosed();
      return connection.commitTransaction();
    }

    @Override
    public Batch createBatch() {
      requireNotClosed();
      return connection.createBatch();
    }

    @Override
    public Publisher<Void> createSavepoint(String name) {
      requireNotClosed();
      return connection.createSavepoint(name);
    }

    @Override
    public Statement createStatement(String sql) {
      requireNotClosed();
      return connection.createStatement(sql);
    }

    @Override
    public boolean isAutoCommit() {
      requireNotClosed();
      return connection.isAutoCommit();
    }

    @Override
    public ConnectionMetadata getMetadata() {
      requireNotClosed();
      return connection.getMetadata();
    }

    @Override
    public IsolationLevel getTransactionIsolationLevel() {
      requireNotClosed();
      return connection.getTransactionIsolationLevel();
    }

    @Override
    public Publisher<Void> releaseSavepoint(String name) {
      requireNotClosed();
      return connection.releaseSavepoint(name);
    }

    @Override
    public Publisher<Void> rollbackTransaction() {
      requireNotClosed();
      return connection.rollbackTransaction();
    }

    @Override
    public Publisher<Void> rollbackTransactionToSavepoint(String name) {
      requireNotClosed();
      return connection.rollbackTransactionToSavepoint(name);
    }

    @Override
    public Publisher<Void> setAutoCommit(boolean autoCommit) {
      requireNotClosed();
      return connection.setAutoCommit(autoCommit);
    }

    @Override
    public Publisher<Void> setTransactionIsolationLevel(
      IsolationLevel isolationLevel) {
      requireNotClosed();
      return connection.setTransactionIsolationLevel(isolationLevel);
    }

    @Override
    public Publisher<Boolean> validate(ValidationDepth depth) {
      requireNotClosed();
      return connection.validate(depth);
    }

    private void requireNotClosed() {
      if (isClosed)
        throw new IllegalStateException("Connection is closed");
    }
  }
}
