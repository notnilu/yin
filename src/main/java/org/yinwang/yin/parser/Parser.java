package org.yinwang.yin.parser;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin._;
import org.yinwang.yin.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Parser {

    public static Node parse(String file) {
        PreParser p = new PreParser(file);
        Node prenode = p.parse();
        return parseNode(prenode);
    }


    public static Node parseNode(Node prenode) {

        // group attribute access first
        prenode = groupAttr(prenode);

        // initial program is in a block
        if (prenode instanceof Block) {
            List<Node> parsed = parseList(((Block) prenode).statements);
            return new Block(parsed, prenode.file, prenode.start, prenode.end, prenode.line, prenode.col);
        }

        if (prenode instanceof Attr) {
            Attr a = (Attr) prenode;
            return new Attr(parseNode(a.value), a.attr, a.file, a.start, a.end, a.line, a.col);
        }

        if (prenode instanceof Subscript) {
            Subscript a = (Subscript) prenode;
            return new Subscript(parseNode(a.value), parseNode(a.index), a.file, a.start, a.end, a.line, a.col);
        }

        // most structures are encoded in a tuple
        // (if t c a) (+ 1 2) (f x y) ...
        // decode them by their first map
        if (prenode instanceof Tuple) {
            Tuple tuple = ((Tuple) prenode);

            if (tuple.elements.isEmpty()) {
                _.abort(tuple, "syntax error");
            }

            if (delimType(tuple.open, Constants.RECORD_BEGIN)) {
                return new RecordLiteral(parseList(tuple.elements), tuple.file, tuple.start, tuple.end, tuple.line,
                        tuple.col);
            }

            if (delimType(tuple.open, Constants.ARRAY_BEGIN)) {
                return new VectorLiteral(parseList(tuple.elements), tuple.file, tuple.start, tuple.end, tuple.line,
                        tuple.col);
            }

            Node keyNode = tuple.elements.get(0);

            if (keyNode instanceof Name) {
                String keyword = ((Name) keyNode).id;

                if (keyword.equals(Constants.SEQ_KEYWORD)) {
                    List<Node> statements = parseList(tuple.elements.subList(1, tuple.elements.size()));
                    return new Block(statements, prenode.file, prenode.start, prenode.end, prenode.line, prenode.col);
                }

                if (keyword.equals(Constants.IF_KEYWORD)) {
                    if (tuple.elements.size() == 4) {
                        Node test = parseNode(tuple.elements.get(1));
                        Node conseq = parseNode(tuple.elements.get(2));
                        Node alter = parseNode(tuple.elements.get(3));
                        return new If(test, conseq, alter, prenode.file, prenode.start, prenode.end, prenode.line,
                                prenode.col);
                    }
                }

                if (keyword.equals(Constants.DEF_KEYWORD)) {
                    if (tuple.elements.size() == 3) {
                        Node pattern = parseNode(tuple.elements.get(1));
                        Node value = parseNode(tuple.elements.get(2));
                        return new Def(pattern, value, prenode.file, prenode.start, prenode.end, prenode.line,
                                prenode.col);
                    } else {
                        _.abort(tuple, "incorrect format of definition");
                    }
                }

                if (keyword.equals(Constants.ASSIGN_KEYWORD)) {
                    if (tuple.elements.size() == 3) {
                        Node pattern = parseNode(tuple.elements.get(1));
                        Node value = parseNode(tuple.elements.get(2));
                        return new Assign(pattern, value, prenode.file, prenode.start, prenode.end, prenode.line,
                                prenode.col);
                    } else {
                        _.abort(tuple, "incorrect format of definition");
                    }
                }

                if (keyword.equals(Constants.FUN_KEYWORD)) {
                    if (tuple.elements.size() >= 3) {
                        Node preParams = tuple.elements.get(1);
                        if (preParams instanceof Tuple) {
                            List<Node> params = parseList(((Tuple) preParams).elements);
                            List<Name> paramNames = new ArrayList<>();
                            for (Node p : params) {
                                if (p instanceof Name) {
                                    paramNames.add((Name) p);
                                } else {
                                    _.abort(p, "parameter msut be a name");
                                    return null;
                                }
                            }
                            List<Node> statements = parseList(tuple.elements.subList(2, tuple.elements.size()));
                            int start = statements.get(0).start;
                            int end = statements.get(statements.size() - 1).end;
                            Node body = new Block(statements, prenode.file, start, end, prenode.line, prenode.col);
                            return new Fun(paramNames, body, prenode.file, prenode.start, prenode.end,
                                    prenode.line, prenode.col);
                        } else {
                            _.abort(preParams, "incorrect format of parameters");
                        }
                    } else {
                        _.abort(tuple, "syntax error in function definition");
                    }
                }

                if (keyword.equals(Constants.RECORD_KEYWORD)) {
                    if (tuple.elements.size() >= 2) {
                        Node name = tuple.elements.get(1);
                        Node maybeParents = tuple.elements.get(2);

                        List<Name> parents;
                        List<Node> defs;

                        if (name instanceof Name) {
                            if (maybeParents instanceof Tuple &&
                                    delimType(((Tuple) maybeParents).open, Constants.TUPLE_BEGIN))
                            {
                                List<Node> parentNodes = ((Tuple) maybeParents).elements;
                                parents = new ArrayList<>();
                                for (Node p : parentNodes) {
                                    if (p instanceof Name) {
                                        parents.add((Name) p);
                                    } else {
                                        _.abort(p, "parents can only be names");
                                    }
                                }
                                defs = tuple.elements.subList(3, tuple.elements.size());
                            } else {
                                parents = null;
                                defs = tuple.elements.subList(2, tuple.elements.size());
                            }

                            Scope properties = new Scope();
                            for (Node def : defs) {
                                if (def instanceof Tuple &&
                                        delimType(((Tuple) maybeParents).open, Constants.ARRAY_BEGIN))
                                {
                                    List<Node> elements = ((Tuple) def).elements;
                                    if (elements.size() > 0) {
                                        Node nameNode = elements.get(0);
                                        if (nameNode instanceof Name) {
                                            String id = ((Name) nameNode).id;
                                            Map<String, Node> props = parseMap(elements.subList(1, elements.size()));
                                            Map<String, Object> propsObj = new LinkedHashMap<>();
                                            for (Map.Entry<String, Node> e : props.entrySet()) {
                                                propsObj.put(e.getKey(), e.getValue());
                                            }
                                            properties.putProperties(id, propsObj);
                                        } else {
                                            _.abort(nameNode, "expect field name, but got: " + nameNode);
                                        }
                                    } else {
                                        _.abort(def, "empty record slot not allowed");
                                    }
                                }
                            }


                            return new RecordDef((Name) name, parents, properties, prenode.file,
                                    prenode.start, prenode.end, prenode.line, prenode.col);
                        } else {
                            _.abort(name, "syntax error in record name: " + name);
                        }
                    } else {
                        _.abort(tuple, "syntax error in record type definition");
                    }
                }
            }
            // application
            Node func = parseNode(tuple.elements.get(0));
            List<Node> parsedArgs = parseList(tuple.elements.subList(1, tuple.elements.size()));
            Argument args = new Argument(parsedArgs);
            return new Call(func, args, prenode.file, prenode.start, prenode.end, prenode.line, prenode.col);
        }

        // defaut return the node untouched
        return prenode;
    }


    public static List<Node> parseList(List<Node> prenodes) {
        List<Node> parsed = new ArrayList<>();
        for (Node s : prenodes) {
            parsed.add(parseNode(s));
        }
        return parsed;
    }


    // treat the list of nodes as key-value pairs like (:x 1 :y 2)
    public static Map<String, Node> parseMap(List<Node> prenodes) {
        Map<String, Node> ret = new LinkedHashMap<>();
        if (prenodes.size() % 2 != 0) {
            _.abort("must be of the form (:key1 value1 :key2 value2), but got: " + prenodes);
            return null;
        }

        for (int i = 0; i < prenodes.size(); i += 2) {
            Node key = parseNode(prenodes.get(i));
            Node value = parseNode(prenodes.get(i + 1));
            if (!(key instanceof Keyword)) {
                _.abort(key, "key must be a keyword, but got: " + key);
                return null;
            } else {
                ret.put(((Keyword) key).id, value);
            }
        }
        return ret;
    }


    public static Node groupAttr(Node prenode) {
        if (prenode instanceof Tuple) {
            Tuple t = (Tuple) prenode;
            List<Node> elements = t.elements;
            List<Node> newElems = new ArrayList<>();

            if (elements.size() >= 1) {
                Node grouped = elements.get(0);
                if (isAttrSub(grouped)) {
                    _.abort(grouped, "illegal keyword: " + grouped);
                    return null;
                }

                grouped = groupAttr(grouped);

                for (int i = 1; i < elements.size(); i++) {
                    Node n1 = elements.get(i);
                    if (isAttrSub(n1)) {
                        if (i + 1 >= elements.size()) {
                            _.abort(n1, "illegal position for .");
                            return null;
                        } else {
                            Node n2 = elements.get(i + 1);
                            if (delimType(n1, Constants.ATTRIBUTE_ACCESS)) {
                                if (n2 instanceof Name) {
                                    grouped = new Attr(grouped, (Name) n2, grouped.file,
                                            grouped.start, n2.end, grouped.line, grouped.col);
                                    i++;   // skip
                                } else {
                                    _.abort(n2, "attribute is not a name");
                                }
                            } else if (delimType(n1, Constants.SUBSCRIPT_ACCESS)) {
                                grouped = new Subscript(grouped, n2, grouped.file,
                                        grouped.start, n2.end, grouped.line, grouped.col);
                                i++;   // skip
                            }
                        }
                    } else {
                        newElems.add(grouped);
                        grouped = n1;
                    }
                }
                newElems.add(grouped);
            }
            return new Tuple(newElems, t.open, t.close, t.file, t.start, t.end, t.line, t.col);
        } else {
            return prenode;
        }
    }


    public static boolean delimType(Node c, String d) {
        if (c instanceof Delimeter) {
            return ((Delimeter) c).shape.equals(d);
        } else {
            return false;
        }
    }


    public static boolean isAttrSub(Node c) {
        return delimType(c, Constants.ATTRIBUTE_ACCESS) ||
                delimType(c, Constants.SUBSCRIPT_ACCESS);
    }


    public static void main(String[] args) {
        Node tree = Parser.parse(args[0]);
        _.msg(tree.toString());
    }

}
