/*
 * Decompiled with CFR 0_110.
 */
package edu.stanford.crypto.database;

import java.net.UnknownHostException;
import java.sql.*;
import java.util.Arrays;
import java.util.List;

public final class Database {
    public static final String URLBase = "jdbc:postgresql://127.0.0.1/";
    public static String URL = "jdbc:postgresql://127.0.0.1/provisions";
    public static final String USER = "admin";
    public static final String PASSWORD = "password";

    private Database() throws UnknownHostException, ClassNotFoundException, SQLException {
    }

    public static void setDatabase(String database) throws UnknownHostException {
        List<String> dbs = Arrays.asList("provisions", "verifier", "prover");
        if (!dbs.contains(database)) {
            throw new UnknownHostException("unknown database: "+database);
        }
        URL = URLBase + database;
    }

    public static Connection getConnection() throws SQLException {
        DriverManager.setLoginTimeout(10);
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    public static void clearAccounts() {
        // customer_balance table sort of being overloaded rn
        executeStatement("TRUNCATE TABLE customer_balance CASCADE");
    }

    public static void clearProofs() {
        executeStatement("TRUNCATE TABLE balance_proof,balance_proof_secrets,asset_proof");
    }

    public static void executeStatement(String command) {
        try {
            Connection connection = Database.getConnection();
            Throwable throwable = null;
            try {
                Statement statement = connection.createStatement();
                statement.execute(command);
            }
            catch (Throwable statement) {
                throwable = statement;
                throw statement;
            }
            finally {
                if (connection != null) {
                    if (throwable != null) {
                        try {
                            connection.close();
                        }
                        catch (Throwable statement) {
                            throwable.addSuppressed(statement);
                        }
                    } else {
                        connection.close();
                    }
                }
            }
        }
        catch (SQLException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    public static void deleteTables() throws SQLException {
        List<String> commands = Arrays.asList(
                "DROP TABLE asset_proof;\n",
                "DROP TABLE asset_proof_secrets;\n",
                "DROP TABLE assets_proof;\n",
                "DROP TABLE balance_proof;\n",
                "DROP TABLE balance_proof_secrets;\n",
                "DROP TABLE blockchain;\n",
                "DROP TABLE customer_balance;\n"
        );
        Connection connection = Database.getConnection();
        for (String s : commands) {
            try {
                PreparedStatement createBlockchain = connection.prepareStatement(s);
                createBlockchain.execute();
            } catch (SQLException e) {
               // table may already be deleted
            }
        }
        connection.close();
    }

    public static void createTables() throws SQLException {
        Connection connection = Database.getConnection();
        String CREATE_BALANCE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS balance_proof (customer_id_hash BYTEA NOT NULL,\n" +
        "range_proof BYTEA,\n" +
                "  CONSTRAINT balance_proof_pkey PRIMARY KEY (customer_id_hash)\n" +
                ")WITH (  OIDS=FALSE\n" +
                ");\n" +
                "ALTER TABLE balance_proof\n" +
                "  OWNER TO admin;\n";
        PreparedStatement createBalanceStatement = connection.prepareStatement(CREATE_BALANCE_TABLE_SQL);
        createBalanceStatement.execute();
        String CREATE_CUSTOMER_BALANCE = "CREATE TABLE IF NOT EXISTS customer_balance\n" +
                "(\n" +
                "  customer_id CHARACTER VARYING(9) NOT NULL,\n" +
                "  balance BIGINT,\n" +
                "  balance_randomness BYTEA,\n" +
                "  CONSTRAINT customer_balance_pkey PRIMARY KEY (customer_id)\n" +
                ")\n" +
                "WITH (\n" +
                "  OIDS=FALSE\n" +
                ");\n" +
                "ALTER TABLE customer_balance\n" +
                "  OWNER TO admin;\n";
        PreparedStatement createCustomerBalance = connection.prepareStatement(CREATE_CUSTOMER_BALANCE);
        createCustomerBalance.execute();
        String CREATE_BALANCE_SECRETS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS balance_proof_secrets\n" +
                "(\n" +
                "  customer_id CHARACTER VARYING(9) NOT NULL,\n" +
                "  hash_salt BYTEA,\n" +
                "  balance_salt BYTEA,\n" +
                "  CONSTRAINT balance_proof_secrets_pkey PRIMARY KEY (customer_id),\n" +
                "  CONSTRAINT balance_proof_secrets_customer_id_fkey FOREIGN KEY (customer_id)\n" +
                "      REFERENCES customer_balance (customer_id) MATCH SIMPLE\n" +
                "      ON UPDATE CASCADE ON DELETE CASCADE\n" +
                ")\n" +
                "WITH (\n" +
                "  OIDS=FALSE\n" +
                ");\n" +
                "ALTER TABLE balance_proof_secrets\n" +
                "  OWNER TO admin;";
        PreparedStatement createBalanceSecretsTable = connection.prepareStatement(CREATE_BALANCE_SECRETS_TABLE_SQL);
        createBalanceSecretsTable.execute();
        String CREATE_ASSETS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS assets_proof(hash_salt BYTEA,balance_salt BYTEA);";
        PreparedStatement createAssetsTable = connection.prepareStatement(CREATE_ASSETS_TABLE_SQL);
        createAssetsTable.execute();
        String CREATE_BLOCKCHAIN_SQL = "CREATE TABLE IF NOT EXISTS blockchain\n" +
                "(\n" +
                "  public_key BYTEA NOT NULL,\n" +
                "  balance BIGINT,\n" +
                "  CONSTRAINT blockchain_pkey PRIMARY KEY (public_key)\n" +
                ")\n" +
                "WITH (\n" +
                "  OIDS=FALSE\n" +
                ");\n" +
                "ALTER TABLE blockchain\n" +
                "  OWNER TO admin;";
        PreparedStatement createBlockchain = connection.prepareStatement(CREATE_BLOCKCHAIN_SQL);
        createBlockchain.execute();
        String CREATE_ASSET_PROOF_SQL = "CREATE TABLE IF NOT EXISTS asset_proof\n" +
                "(\n" +
                "  public_key BYTEA NOT NULL,\n" +
                "  main_proof BYTEA,\n" +
                "  binary_proof BYTEA,\n" +
                "  CONSTRAINT asset_proof_pkey PRIMARY KEY (public_key),\n" +
                "  CONSTRAINT asset_proof_public_key_fkey FOREIGN KEY (public_key)\n" +
                "      REFERENCES blockchain (public_key) MATCH SIMPLE\n" +
                "      ON UPDATE NO ACTION ON DELETE NO ACTION\n" +
                ")\n" +
                "WITH (\n" +
                "  OIDS=FALSE\n" +
                ");\n" +
                "ALTER TABLE asset_proof\n" +
                "  OWNER TO admin;\n";
        PreparedStatement createAssetProof = connection.prepareStatement(CREATE_ASSET_PROOF_SQL);
        createAssetProof.execute();
        String CREATE_ASSET_PROOF_SECRETS_SQL = "CREATE TABLE IF NOT EXISTS asset_proof_secrets \n" +
                "(\n" +
                "  public_key BYTEA NOT NULL,\n" +
                "  private_key BYTEA,\n" +
                "  CONSTRAINT asset_proof_secrets_pkey PRIMARY KEY (public_key),\n" +
                "  CONSTRAINT asset_proof_secrets_public_key_fkey FOREIGN KEY (public_key)\n" +
                "      REFERENCES blockchain (public_key) MATCH SIMPLE\n" +
                "      ON UPDATE NO ACTION ON DELETE NO ACTION\n" +
                ")\n" +
                "WITH (\n" +
                "  OIDS=FALSE\n" +
                ");\n" +
                "ALTER TABLE asset_proof_secrets\n" +
                "  OWNER TO admin;\n";
        PreparedStatement createAssetProofSecrets = connection.prepareStatement(CREATE_ASSET_PROOF_SECRETS_SQL);
        createAssetProofSecrets.execute();
        connection.close();
    }
}

