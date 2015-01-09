package com.trebogeer.jcql;


import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.TupleType;
import com.datastax.driver.core.UserType;
import com.datastax.driver.mapping.annotations.Column;
import com.datastax.driver.mapping.annotations.Table;
import com.datastax.driver.mapping.annotations.UDT;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JVar;
import com.sun.codemodel.writer.SingleStreamCodeWriter;
import org.javatuples.Pair;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hello world!
 */

public class JCQL {

    private static final Logger logger = LoggerFactory.getLogger("JCQL.LOG");

    private Options cfg;

    private JCQL(Options cfg) {
        this.cfg = cfg;
    }

    public static void main(String[] args) {
        Options cfg = new Options();
        CmdLineParser parser = new CmdLineParser(cfg);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            throw new RuntimeException(e);
        }
        JCQL jcql = new JCQL(cfg);
        try {
            jcql.exec();
            logger.info("Done!");
        } catch (IOException e) {
            logger.error("Failed to write generated code due to : ", e);
        }

    }

    public void exec() throws IOException {
        String keyspace = cfg.keysapce;
        Cluster c = Cluster.builder().addContactPoint(cfg.dbHost).withPort(Integer.valueOf(cfg.dbPort)).build();
        Session s = c.connect(keyspace);
        Multimap<String, Pair<String, DataType>> beans = HashMultimap.create();
        Multimap<String, Pair<String, ColumnMetadata>> tables = HashMultimap.create();
        ArrayListMultimap<String, String> partitionKeys = ArrayListMultimap.create();

        Collection<UserType> types = s.getCluster().getMetadata().getKeyspace(keyspace).getUserTypes();

        for (UserType t : types) {
            String name = t.getTypeName();
            Set<Pair<String, DataType>> fields = new HashSet<Pair<String, DataType>>();
            for (String field : t.getFieldNames()) {
                DataType dt = t.getFieldType(field);
                fields.add(Pair.with(field, dt));
            }
            beans.putAll(name, fields);
        }
        Collection<TableMetadata> tbls = s.getCluster().getMetadata().getKeyspace(keyspace).getTables();
        for (TableMetadata t : tbls) {
            String name = t.getName();
            for (ColumnMetadata clmdt : t.getPartitionKey()) {
                partitionKeys.put(name, clmdt.getName());
            }
            partitionKeys.trimToSize();
            Set<Pair<String, ColumnMetadata>> fields = new HashSet<Pair<String, ColumnMetadata>>();
            for (ColumnMetadata field : t.getColumns()) {
                fields.add(Pair.with(field.getName(), field));
            }
            tables.putAll(name, fields);
        }

        s.close();
        c.close();

        JCodeModel model = generateCode(beans, tables, partitionKeys);
        if ("y".equalsIgnoreCase(cfg.debug)) {
            model.build(new SingleStreamCodeWriter(System.out));
        } else {
            File source = new File(cfg.generatedSourceDir);
            if (source.exists() || source.mkdirs()) {
                model.build(new File(cfg.generatedSourceDir));
            }
        }
    }

    public JCodeModel generateCode(
            Multimap<String, Pair<String, DataType>> beans,
            Multimap<String, Pair<String, ColumnMetadata>> tables,
            ArrayListMultimap<String, String> partitionKeys) {
        JCodeModel model = new JCodeModel();
        for (String cl : beans.keySet()) {
            try {
                JDefinedClass clazz = JCQLUtils.getBeanClass(cfg.jpackage, JCQLUtils.camelize(cl), model);
                clazz.annotate(UDT.class).param("keyspace", cfg.keysapce).param("name", cl);
                for (Pair<String, DataType> field : beans.get(cl)) {
                    javaBeanFieldWithGetterSetter(clazz, model, field.getValue1(), field.getValue0(),
                            -1, com.datastax.driver.mapping.annotations.Field.class);

                }
            } catch (JClassAlreadyExistsException e) {
                logger.warn("Class '{}' already exists for UDT, skipping ", cl);
            }

        }

        for (String table : tables.keySet()) {
            try {
                JDefinedClass clazz = JCQLUtils.getBeanClass(cfg.jpackage, JCQLUtils.camelize(table), model);
                clazz.annotate(Table.class).param("keyspace", cfg.keysapce).param("name", table);
                List<String> pkList = partitionKeys.get(table);
                Set<String> pks = new HashSet<String>(pkList);

                for (Pair<String, ColumnMetadata> field : tables.get(table)) {
                    String fieldName = field.getValue0();
                    int order = 0;
                    if (pks.contains(fieldName) && pks.size() > 1) {
                        order = pkList.indexOf(field.getValue0());
                    }
                    javaBeanFieldWithGetterSetter(clazz, model, field.getValue1().getType(), fieldName,
                            order, Column.class);


                }
            } catch (JClassAlreadyExistsException ex) {
                logger.warn("Class '{}' already exists for table, skipping ", table);
            }
        }
        return model;
    }

    private void javaBeanFieldWithGetterSetter(
            JDefinedClass clazz, JCodeModel model,
            DataType dt, String name, int pko,
            Class<? extends Annotation> ann) {
        JClass ref = getType(dt, model);

        JFieldVar f = clazz.field(JMod.PRIVATE, ref, JCQLUtils.camelize(name, true));
        if (ann != null) {
            f.annotate(ann).param("name", name);
        }
        if (dt.isFrozen()) {
            f.annotate(com.datastax.driver.mapping.annotations.Frozen.class);
        }
        if (pko == 0) {
            f.annotate(com.datastax.driver.mapping.annotations.PartitionKey.class);
        } else if (pko > 0) {
            f.annotate(com.datastax.driver.mapping.annotations.PartitionKey.class).param("value", pko);
        }
        clazz.method(JMod.PUBLIC, ref, "get" + JCQLUtils.camelize(name)).body()._return(JExpr._this().ref(f));
        JMethod m = clazz.method(JMod.PUBLIC, ref, "set" + JCQLUtils.camelize(name));
        JVar p = m.param(ref, JCQLUtils.camelize(name, true));
        m.body().assign(JExpr._this().ref(f), p);
    }

    private JClass getType(DataType t, JCodeModel model) {
        if (t.isCollection()) {
            JClass ref = model.ref(t.asJavaClass());
            List<DataType> typeArgs = t.getTypeArguments();
            if (typeArgs.size() == 1) {
                DataType arg = typeArgs.get(0);
                if (arg instanceof UserType) {
                    UserType ut = (UserType) arg;
                    return ref.narrow(model.ref(cfg.jpackage + "." + JCQLUtils.camelize(ut.getTypeName())));
                } else if (arg instanceof TupleType) {
                    TupleType tt = (TupleType) arg;
                    List<DataType> dt = tt.getComponentTypes();
                    // TODO figure out how cassandra standard mappers deal with tuples

                    JClass dts[] = new JClass[dt.size()];
                    for (int i = 0; i < dts.length; i++) {
                        dts[i] = getType(dt.get(i), model);
                    }
                    return ref.narrow(dts);
                }

            } else if (typeArgs.size() == 2) {
                DataType arg0 = typeArgs.get(0);
                DataType arg1 = typeArgs.get(1);
                JClass argc0 = getType(arg0, model);
                JClass argc1 = getType(arg1, model);
                return ref.narrow(argc0, argc1);
            }
            return ref;
        } else if (t.isFrozen()) {
            if (t instanceof UserType) {
                UserType ut = (UserType) t;
                return model.ref(cfg.jpackage + "." + JCQLUtils.camelize(ut.getTypeName()));
            } else if (t instanceof TupleType) {
                // TODO figure out how cassandra standard mappers deal with tuples
                // and what are they mapped to
             /*   TupleType tt = (TupleType) t;
                List<DataType> dt = tt.getComponentTypes();
                JClass dts[] = new JClass[dt.size()];
                for (int i = 0; i < dts.length; i++) {
                    dts[i] = getType(dt.get(i), model);
                }
                return ref.narrow(dts);*/
            }
            return model.ref(cfg.jpackage + "." + JCQLUtils.camelize(t.getName().name()));
        } else {
            return model.ref(t.asJavaClass());
        }
    }

}
