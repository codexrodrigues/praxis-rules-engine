package org.praxisplatform.rules.jsonlogic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.rules.jsonlogic.internal.MissingValue;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.jsonlogic.model.*;
import org.praxisplatform.rules.jsonlogic.operators.*;

/** Thread-safe, stateless and deterministic Praxis JSON Logic runtime. */
public final class PraxisJsonLogicEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ROOTS = Set.of("form","row","rowData","computed","meta","source","event","payload","state","context");
    private static final List<JsonLogicOperatorDescriptor> NATIVE = nativeDescriptors();
    private final PraxisJsonLogicOperatorRegistry registry;

    /** Creates an engine with the canonical native and Praxis operator catalog. */
    public PraxisJsonLogicEngine() { this(new PraxisJsonLogicOperatorRegistry(DefaultPraxisJsonLogicOperators.all())); }
    /**
     * Creates an engine from a prevalidated extension registry.
     * @param registry Praxis and host extension registry
     */
    public PraxisJsonLogicEngine(PraxisJsonLogicOperatorRegistry registry) { this.registry = registry; }
    /**
     * Returns a new engine enriched with trusted, namespaced host operators.
     * @param operators host-owned operator implementations
     * @return new engine; this engine remains unchanged
     */
    public PraxisJsonLogicEngine withHostOperators(List<PraxisJsonLogicOperator> operators) { return new PraxisJsonLogicEngine(registry.withHostOperators(operators)); }
    /**
     * Publishes native and extension contracts for discovery and authoring tools.
     * @return descriptors in deterministic catalog order
     */
    public List<JsonLogicOperatorDescriptor> listOperatorDescriptors() { return java.util.stream.Stream.concat(NATIVE.stream(),registry.descriptors().stream()).toList(); }

    /**
     * Evaluates an expression against business data under explicit deterministic context.
     * @param expression JSON Logic expression
     * @param data business data visible to variable resolution
     * @param options roots, frozen temporal context, and resource limits
     * @return public Java value produced by the expression
     * @throws PraxisJsonLogicException when the expression is invalid or exceeds a limit
     */
    public Object evaluate(JsonNode expression, JsonNode data, JsonLogicEvaluationOptions options) {
        JsonLogicEvaluationContext ctx=context(data,options); checkSize(expression,ctx.limits());
        Object value=evaluateNode(expression,ctx,new Budget(ctx.limits()),0);
        Object result=publicValue(value);
        checkResult(result,ctx.limits());
        return result;
    }
    /**
     * Evaluates and exposes both the value and its canonical truthiness.
     * @param e expression
     * @param d business data
     * @param o evaluation options
     * @return combined evaluation result
     */
    public JsonLogicEvaluationResult evaluateResult(JsonNode e,JsonNode d,JsonLogicEvaluationOptions o){Object v=evaluate(e,d,o);return new JsonLogicEvaluationResult(v,truthy(v));}
    /**
     * Tests a condition, treating an absent condition as an unconditional match.
     * @param e condition expression
     * @param d business data
     * @param o evaluation options
     * @return whether the condition matches
     */
    public boolean matches(JsonNode e,JsonNode d,JsonLogicEvaluationOptions o){return e==null||e.isNull()||evaluateResult(e,d,o).truthy();}
    /**
     * Validates a rule and throws the first deterministic diagnostic when invalid.
     * @param e expression
     * @param o validation options
     * @throws PraxisJsonLogicException when validation fails
     */
    public void validate(JsonNode e,JsonLogicValidationOptions o){JsonLogicValidationResult r=validateResult(e,o);if(!r.valid()){var x=r.issues().get(0);throw new PraxisJsonLogicException(x.code(),x.message(),x.path(),x.operator());}}
    /**
     * Validates a rule without executing it or throwing for rule defects.
     * @param e expression
     * @param o validation options
     * @return ordered validation outcome
     */
    public JsonLogicValidationResult validateResult(JsonNode e,JsonLogicValidationOptions o){List<JsonLogicValidationIssue> issues=new ArrayList<>();JsonLogicEvaluationContext c=context(MAPPER.createObjectNode(),o==null?null:o.toEvaluationOptions());try{checkSize(e,c.limits());if(Boolean.TRUE.equals(o==null?null:o.requireExpressionObject())&&!(e!=null&&e.isObject()&&e.size()==1)){issue(issues,JsonLogicIssueCode.RULE_SHAPE_INVALID,"JSON Logic condition must be a single-operator object.","$",null);return new JsonLogicValidationResult(false,issues);}validateNode(e,c,"$",issues,true,new Budget(c.limits()),0);}catch(PraxisJsonLogicException ex){issue(issues,ex.getCode(),ex.getMessage(),ex.getPath(),ex.getOperator());}return new JsonLogicValidationResult(issues.isEmpty(),issues);}
    /**
     * Applies the canonical cross-runtime JSON Logic truthiness rules.
     * @param v runtime value
     * @return canonical truthiness
     */
    public boolean truthy(Object v){if(v==null||v==MissingValue.INSTANCE)return false;if(v instanceof Boolean b)return b;if(v instanceof Number n)return number(n).compareTo(BigDecimal.ZERO)!=0;if(v instanceof String s)return!s.isEmpty();if(v instanceof List<?>l)return!l.isEmpty();return true;}
    /**
     * Compares values without coercion while normalizing numeric representations.
     * @param a left value
     * @param b right value
     * @return whether values are strictly equal
     */
    public boolean equalsStrict(Object a,Object b){if(a==MissingValue.INSTANCE||b==MissingValue.INSTANCE)return a==b;if(a instanceof Number&&b instanceof Number)return number((Number)a).compareTo(number((Number)b))==0;if(a==null||b==null)return a==b;if(a instanceof List<?>x&&b instanceof List<?>y)return x.size()==y.size()&&java.util.stream.IntStream.range(0,x.size()).allMatch(i->equalsStrict(x.get(i),y.get(i)));if(a instanceof Map<?,?>x&&b instanceof Map<?,?>y)return mapEquals(x,y);return a.getClass()==b.getClass()&&a.equals(b);}
    /**
     * Resolves a closed-subset path from a host operator object argument.
     * @param o source object
     * @param p Praxis path
     * @return resolved public value, or {@code null} when absent
     */
    public Object readPathFromObject(Map<?,?>o,String p){return publicValue(readPath(MAPPER.valueToTree(o),PraxisPath.parse(p)));}
    /**
     * Tests path presence independently from an explicit null value.
     * @param o source object
     * @param p Praxis path
     * @return whether the path exists
     */
    public boolean hasPathInObject(Map<?,?>o,String p){return readPath(MAPPER.valueToTree(o),PraxisPath.parse(p))!=MissingValue.INSTANCE;}
    /**
     * Serializes collection values as JSON and scalar values canonically as text.
     * @param v runtime value
     * @return deterministic string representation
     */
    public String stringify(Object v){try{return v instanceof Map<?,?>||v instanceof List<?>?MAPPER.writeValueAsString(v):String.valueOf(v);}catch(Exception e){throw new PraxisJsonLogicException(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Value cannot be stringified.");}}

    /**
     * Validates a host-produced value against the same deterministic structural limits as JSON Logic results.
     * @param value value crossing the runtime boundary
     * @param limits explicit limits, or defaults when {@code null}
     * @throws PraxisJsonLogicException when the value exceeds a deterministic limit
     */
    public void validateResultValue(Object value, JsonLogicLimits limits) {
        checkResult(value, limits == null ? JsonLogicLimits.DEFAULT : limits);
    }

    private Object evaluateNode(JsonNode n,JsonLogicEvaluationContext c,Budget b,int depth){b.visit(depth);if(n==null||n.isNull())return null;if(n.isNumber())return n.decimalValue();if(n.isTextual()){b.string(n.textValue().length());return n.textValue();}if(n.isBoolean())return n.booleanValue();if(n.isArray()){b.array(n.size());List<Object>r=new ArrayList<>();for(JsonNode x:n)r.add(evaluateNode(x,c,b,depth+1));return r;}if(!n.isObject())return MAPPER.convertValue(n,Object.class);ObjectNode o=(ObjectNode)n;if(o.size()!=1){Map<String,Object>m=new LinkedHashMap<>();o.properties().forEach(x->m.put(x.getKey(),evaluateNode(x.getValue(),c,b,depth+1)));return m;}var en=o.properties().iterator().next();String op=en.getKey();JsonNode raw=en.getValue();b.operation();return switch(op){case"var"->evalVar(raw,c,b,depth);case"and"->evalAnd(raw,c,b,depth);case"or"->evalOr(raw,c,b,depth);case"if"->evalIf(raw,c,b,depth);case"!"->!truthy(argValues(op,raw,c,b,depth,1,1).get(0));case"!!"->truthy(argValues(op,raw,c,b,depth,1,1).get(0));case"==","===","!=","!==",">",">=","<","<="->comparison(op,argValues(op,raw,c,b,depth,2,2));case"+","-","*","/","%","min","max"->arithmetic(op,argValues(op,raw,c,b,depth,op.equals("/")?2:1,op.equals("%")?2:null));case"in"->in(argValues(op,raw,c,b,depth,2,2));case"cat"->argValues(op,raw,c,b,depth,1,null).stream().map(v->v==null||v==MissingValue.INSTANCE?"":String.valueOf(v)).reduce("",String::concat);case"substr"->substr(argValues(op,raw,c,b,depth,2,3));case"merge"->merge(argValues(op,raw,c,b,depth,1,null),b);case"map","filter","reduce","all","some","none"->higher(op,raw,c,b,depth);default->custom(op,raw,c,b,depth);};}
    private Object evalVar(JsonNode raw, JsonLogicEvaluationContext context, Budget budget, int depth) {
        String path;
        JsonNode defaultValue = null;
        if (raw.isTextual()) {
            path = raw.textValue();
        } else if (raw.isArray() && (raw.size() == 1 || raw.size() == 2)) {
            if (!raw.get(0).isTextual()) {
                throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,
                        "`var` requires a string path as the first argument.", "$", "var");
            }
            path = raw.get(0).textValue();
            if (raw.size() == 2) defaultValue = raw.get(1);
        } else {
            throw error(JsonLogicIssueCode.RULE_ARITY_INVALID,
                    "`var` requires a string path or [path, defaultValue].", "$", "var");
        }
        Object value = resolve(path, context);
        return value == MissingValue.INSTANCE && defaultValue != null
                ? evaluateNode(defaultValue, context, budget, depth + 1)
                : value;
    }
    private Object evalAnd(JsonNode r,JsonLogicEvaluationContext c,Budget b,int d){List<JsonNode>a=args(r,"and",1,null);Object v=null;for(JsonNode x:a){v=evaluateNode(x,c,b,d+1);if(!truthy(v))return v;}return v;}
    private Object evalOr(JsonNode r,JsonLogicEvaluationContext c,Budget b,int d){List<JsonNode>a=args(r,"or",1,null);Object v=null;for(JsonNode x:a){v=evaluateNode(x,c,b,d+1);if(truthy(v))return v;}return v;}
    private Object evalIf(JsonNode r,JsonLogicEvaluationContext c,Budget b,int d){List<JsonNode>a=args(r,"if",2,null);for(int i=0;i+1<a.size();i+=2)if(truthy(evaluateNode(a.get(i),c,b,d+1)))return evaluateNode(a.get(i+1),c,b,d+1);return a.size()%2==1?evaluateNode(a.get(a.size()-1),c,b,d+1):null;}
    private Object custom(String op,JsonNode r,JsonLogicEvaluationContext c,Budget b,int d){var x=registry.get(op);if(x==null)throw error(JsonLogicIssueCode.RULE_OPERATOR_UNKNOWN,"Unsupported JSON Logic operator: "+op,"$",op);return x.evaluate(argValues(op,r,c,b,d,x.minArgs(),x.maxArgs()),c,this);}
    private List<Object> argValues(String op,JsonNode r,JsonLogicEvaluationContext c,Budget b,int d,int min,Integer max){List<Object>v=new ArrayList<>();for(JsonNode n:args(r,op,min,max))v.add(evaluateNode(n,c,b,d+1));return v;}
    private List<JsonNode> args(JsonNode r,String op,int min,Integer max){List<JsonNode>a=new ArrayList<>();if(r instanceof ArrayNode ar)ar.forEach(a::add);else if(r!=null&&!r.isNull())a.add(r);if(a.size()<min||max!=null&&a.size()>max)throw error(JsonLogicIssueCode.RULE_ARITY_INVALID,"Invalid arity for operator "+op+".","$",op);return a;}
    private Object comparison(String op,List<Object>a){Object l=a.get(0),r=a.get(1);return switch(op){case"=="->equalsLoose(l,r);case"==="->equalsStrict(l,r);case"!="->!equalsLoose(l,r);case"!=="->!equalsStrict(l,r);case">"->compare(l,r)>0;case">="->compare(l,r)>=0;case"<"->compare(l,r)<0;default->compare(l,r)<=0;};}
    private boolean equalsLoose(Object a,Object b){if((a==null||a==MissingValue.INSTANCE)&&(b==null||b==MissingValue.INSTANCE))return true;if(a instanceof Number&&b instanceof String s)return numericString(a,s);if(b instanceof Number&&a instanceof String s)return numericString(b,s);if(a instanceof Boolean x&&b instanceof Number n)return(number(n).compareTo(x?BigDecimal.ONE:BigDecimal.ZERO)==0);if(b instanceof Boolean x&&a instanceof Number n)return(number(n).compareTo(x?BigDecimal.ONE:BigDecimal.ZERO)==0);return equalsStrict(a,b);}
    private boolean numericString(Object n,String s){try{return number((Number)n).compareTo(new BigDecimal(s.trim()))==0;}catch(Exception e){return false;}}
    private int compare(Object a,Object b){if(a instanceof Number x&&b instanceof Number y)return number(x).compareTo(number(y));if(a instanceof String x&&b instanceof String y)return x.compareTo(y);throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Comparison requires operands of the same comparable type.","$",null);}
    private Object arithmetic(String op,List<Object>a){List<BigDecimal>v=a.stream().map(x->x instanceof Number n?number(n):badNumber(op)).toList();try{return switch(op){case"+"->v.stream().reduce(BigDecimal.ZERO,BigDecimal::add);case"-"->v.size()==1?v.get(0).negate():v.subList(1,v.size()).stream().reduce(v.get(0),BigDecimal::subtract);case"*"->v.stream().reduce(BigDecimal.ONE,BigDecimal::multiply);case"/"->v.subList(1,v.size()).stream().reduce(v.get(0),(x,y)->x.divide(y,java.math.MathContext.DECIMAL128));case"%"->v.get(0).remainder(v.get(1));case"min"->v.stream().min(BigDecimal::compareTo).orElseThrow();default->v.stream().max(BigDecimal::compareTo).orElseThrow();};}catch(ArithmeticException ex){throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Operator "+op+" cannot divide by zero.","$",op);}}
    private BigDecimal badNumber(String op){throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Operator "+op+" requires finite numeric operands.","$",op);}
    private boolean in(List<Object>a){Object n=a.get(0),h=a.get(1);if(h instanceof String s&&n instanceof String x)return s.contains(x);if(h instanceof List<?>l)return l.stream().anyMatch(x->equalsStrict(x,n));throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"`in` requires a string or array haystack.","$","in");}
    private String substr(List<Object>a){String s=a.get(0)==null?"":String.valueOf(a.get(0));if(!(a.get(1) instanceof Number startValue)||a.size()==3&&!(a.get(2) instanceof Number))throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Operator substr requires numeric indexes.","$","substr");int start=number(startValue).intValue();int from=start<0?Math.max(s.length()+start,0):Math.min(start,s.length());if(a.size()==2)return s.substring(from);int len=number((Number)a.get(2)).intValue();int to=len<0?Math.max(from,s.length()+len):Math.min(s.length(),from+len);return s.substring(from,to);}
    private List<Object> merge(List<Object>a,Budget b){List<Object>r=new ArrayList<>();for(Object x:a){int added=x instanceof List<?>l?l.size():1;b.array(r.size()+added);if(x instanceof List<?>l)r.addAll(l);else r.add(x);}return r;}
    private Object higher(String op,JsonNode raw,JsonLogicEvaluationContext c,Budget b,int d){List<JsonNode>a=args(raw,op,2,op.equals("reduce")?3:2);Object src=evaluateNode(a.get(0),c,b,d+1);if(!(src instanceof List<?>list))throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Operator "+op+" requires an array.","$",op);b.array(list.size());if(op.equals("map")){List<Object>r=new ArrayList<>();for(Object x:list)r.add(evaluateNode(a.get(1),child(c,x),b,d+1));return r;}if(op.equals("filter")){List<Object>r=new ArrayList<>();for(Object x:list)if(truthy(evaluateNode(a.get(1),child(c,x),b,d+1)))r.add(x);return r;}if(op.equals("reduce")){Object acc=a.size()==3?evaluateNode(a.get(2),c,b,d+1):MissingValue.INSTANCE;for(Object x:list){Map<String,Object>scope=new LinkedHashMap<>();scope.put("current",x);scope.put("accumulator",acc);acc=evaluateNode(a.get(1),child(c,scope),b,d+1);}return acc;}if(op.equals("all")&&list.isEmpty())return false;boolean any=false;for(Object x:list){boolean hit=truthy(evaluateNode(a.get(1),child(c,x),b,d+1));if(op.equals("all")&&!hit)return false;if((op.equals("some")||op.equals("none"))&&hit){any=true;break;}}return op.equals("all")||op.equals("some")?any||op.equals("all"):!any;}
    private JsonLogicEvaluationContext child(JsonLogicEvaluationContext c,Object data){return new JsonLogicEvaluationContext(MAPPER.valueToTree(data),List.of(),null,true,c.nowUtc(),c.userTimeZone(),c.limits());}
    private Object resolve(String p,JsonLogicEvaluationContext c){if(p.isEmpty())return MAPPER.convertValue(c.data(),Object.class);List<String>tokens=PraxisPath.parse(p);String first=tokens.get(0);if(!c.allowImplicitRoot()&&c.availableRoots().size()>1&&!c.availableRoots().contains(first))throw error(JsonLogicIssueCode.RULE_CONTEXT_AMBIGUOUS,"Implicit root is not allowed for path \""+p+"\".","$","var");return readPath(c.data(),tokens);}
    private Object readPath(JsonNode data,List<String>tokens){JsonNode cur=data;for(String s:tokens){if(cur==null||cur.isNull())return MissingValue.INSTANCE;if(cur.isArray()){if(!s.matches("\\d+"))return MissingValue.INSTANCE;int i;try{i=Integer.parseInt(s);}catch(NumberFormatException exception){return MissingValue.INSTANCE;}if(i>=cur.size())return MissingValue.INSTANCE;cur=cur.get(i);}else if(cur.isObject()){if(!cur.has(s))return MissingValue.INSTANCE;cur=cur.get(s);}else return MissingValue.INSTANCE;}return cur==null?MissingValue.INSTANCE:cur.isNull()?null:MAPPER.convertValue(cur,Object.class);}
    private void validateNode(
            JsonNode node,
            JsonLogicEvaluationContext context,
            String path,
            List<JsonLogicValidationIssue> issues,
            boolean top,
            Budget budget,
            int depth) {
        budget.visit(depth);
        if (node == null) return;
        if (node.isTextual()) {
            budget.string(node.textValue().length());
            return;
        }
        if (node.isValueNode()) return;
        if (node.isArray()) {
            budget.array(node.size());
            if (top) issue(issues, JsonLogicIssueCode.RULE_SHAPE_INVALID,
                    "Top-level condition cannot be an array.", path, null);
            for (int index = 0; index < node.size(); index++) {
                validateNode(node.get(index), context, path + "[" + index + "]",
                        issues, false, budget, depth + 1);
            }
            return;
        }
        if (!node.isObject()) return;
        if (node.size() != 1) {
            if (top) issue(issues, JsonLogicIssueCode.RULE_SHAPE_INVALID,
                    "Condition must contain exactly one operator.", path, null);
            for (var child : node.properties()) {
                validateNode(child.getValue(), context, path + "." + child.getKey(),
                        issues, false, budget, depth + 1);
            }
            return;
        }
        var entry = node.properties().iterator().next();
        String operator = entry.getKey();
        JsonNode raw = entry.getValue();
        budget.operation();
        try {
            if (operator.equals("var")) {
                validateVar(raw, context, path, issues, budget, depth);
                return;
            }
            JsonLogicOperatorDescriptor descriptor = descriptor(operator);
            if (descriptor == null) {
                throw error(JsonLogicIssueCode.RULE_OPERATOR_UNKNOWN,
                        "Unsupported JSON Logic operator: " + operator, path, operator);
            }
            List<JsonNode> arguments = args(raw, operator, descriptor.minArgs(), descriptor.maxArgs());
            if (operator.equals("matches") && arguments.size() == 2 && arguments.get(1).isTextual()) {
                org.praxisplatform.rules.jsonlogic.internal.SafeRegex.compile(
                        arguments.get(1).textValue(), context.limits());
            }
            for (int index = 0; index < arguments.size(); index++) {
                validateNode(arguments.get(index), context,
                        path + "." + operator + "[" + index + "]",
                        issues, false, budget, depth + 1);
            }
        } catch (PraxisJsonLogicException exception) {
            issue(issues, exception.getCode(), exception.getMessage(),
                    exception.getPath(), exception.getOperator());
        }
    }

    private void validateVar(
            JsonNode raw,
            JsonLogicEvaluationContext context,
            String path,
            List<JsonLogicValidationIssue> issues,
            Budget budget,
            int depth) {
        String variablePath;
        JsonNode defaultValue = null;
        if (raw != null && raw.isTextual()) {
            variablePath = raw.textValue();
            budget.visit(depth + 1);
            budget.string(variablePath.length());
        } else if (raw != null && raw.isArray()
                && (raw.size() == 1 || raw.size() == 2)
                && raw.get(0).isTextual()) {
            variablePath = raw.get(0).textValue();
            budget.array(raw.size());
            budget.visit(depth + 1);
            budget.string(variablePath.length());
            if (raw.size() == 2) defaultValue = raw.get(1);
        } else if (raw != null && raw.isArray()
                && (raw.size() == 1 || raw.size() == 2)) {
            throw error(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,
                    "`var` requires a string path as the first argument.", path, "var");
        } else {
            throw error(JsonLogicIssueCode.RULE_ARITY_INVALID,
                    "`var` requires a string path or [path, defaultValue].", path, "var");
        }
        if (!variablePath.isEmpty()) {
            List<String> tokens = PraxisPath.parse(variablePath);
            String first = tokens.get(0);
            if (ROOTS.contains(first) && !context.availableRoots().isEmpty()
                    && !context.availableRoots().contains(first)) {
                throw error(JsonLogicIssueCode.RULE_ROOT_UNKNOWN,
                        "Root \"" + first + "\" is unavailable.", path, "var");
            }
            if (!context.allowImplicitRoot() && context.availableRoots().size() > 1
                    && !context.availableRoots().contains(first)) {
                throw error(JsonLogicIssueCode.RULE_CONTEXT_AMBIGUOUS,
                        "Implicit root is not allowed.", path, "var");
            }
        }
        if (defaultValue != null) {
            validateNode(defaultValue, context, path + ".var[1]",
                    issues, false, budget, depth + 1);
        }
    }
    private JsonLogicOperatorDescriptor descriptor(String op){return listOperatorDescriptors().stream().filter(x->x.operator().equals(op)).findFirst().orElse(null);}
    private JsonLogicEvaluationContext context(JsonNode d,JsonLogicEvaluationOptions o){return new JsonLogicEvaluationContext(d==null?MAPPER.createObjectNode():d,o==null?List.of():o.availableRoots(),o==null?null:o.defaultRoot(),o!=null&&Boolean.TRUE.equals(o.allowImplicitRoot())||(o==null||o.availableRoots().size()<=1),o==null?null:o.nowUtc(),o==null?null:o.userTimeZone(),o==null?JsonLogicLimits.DEFAULT:o.limits());}
    private void checkSize(JsonNode e,JsonLogicLimits l){if(e!=null&&e.toString().getBytes(StandardCharsets.UTF_8).length>l.maxExpressionBytes())throw error(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,"Expression exceeds the byte limit.","$",null);}
    private void checkResult(Object value,JsonLogicLimits limits){JsonNode node=MAPPER.valueToTree(value);if(node.toString().getBytes(StandardCharsets.UTF_8).length>limits.maxExpressionBytes())throw error(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,"Result exceeds the byte limit.","$",null);checkResultNode(node,new Budget(limits),0);}
    private void checkResultNode(JsonNode node,Budget budget,int depth){budget.visit(depth);if(node==null||node.isNull())return;if(node.isTextual()){budget.string(node.textValue().length());return;}if(node.isArray()){budget.array(node.size());for(JsonNode child:node)checkResultNode(child,budget,depth+1);return;}if(node.isObject())for(var child:node.properties())checkResultNode(child.getValue(),budget,depth+1);}
    private Object publicValue(Object v){if(v==MissingValue.INSTANCE)return null;if(v instanceof List<?>l)return l.stream().map(this::publicValue).toList();if(v instanceof Map<?,?>m){Map<Object,Object>r=new LinkedHashMap<>();m.forEach((k,x)->r.put(k,publicValue(x)));return r;}return v;}
    private boolean mapEquals(Map<?,?>a,Map<?,?>b){return a.size()==b.size()&&a.entrySet().stream().allMatch(e->b.containsKey(e.getKey())&&equalsStrict(e.getValue(),b.get(e.getKey())));}
    private static BigDecimal number(Number n){try{if(n instanceof Double d&&!Double.isFinite(d)||n instanceof Float f&&!Float.isFinite(f))throw new NumberFormatException();BigDecimal b=n instanceof BigDecimal x?x:new BigDecimal(n.toString());return b.stripTrailingZeros();}catch(Exception e){throw new PraxisJsonLogicException(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"Number must be finite.");}}
    private static PraxisJsonLogicException error(JsonLogicIssueCode c,String m,String p,String o){return new PraxisJsonLogicException(c,m,p,o);}
    private static void issue(List<JsonLogicValidationIssue>i,JsonLogicIssueCode c,String m,String p,String o){i.add(new JsonLogicValidationIssue(c,m,p,o));}
    private static List<JsonLogicOperatorDescriptor> nativeDescriptors(){String[]names={"var","==","===","!=","!==",">",">=","<","<=","!","!!","and","or","if","in","cat","substr","merge","map","filter","reduce","all","some","none","+","-","*","/","%","min","max"};List<JsonLogicOperatorDescriptor>r=new ArrayList<>();for(String n:names){int min=switch(n){case"and","or","cat","merge","+","-","*","min","max"->1;case"!","!!","var"->1;default->2;};Integer max=switch(n){case"!","!!"->1;case"var","in","==","===","!=","!==",">",">=","<","<=","map","filter","all","some","none","%"->2;case"substr","reduce"->3;default->null;};String type=Set.of("==","===","!=","!==",">",">=","<","<=","!","!!","in","all","some","none").contains(n)?"boolean":Set.of("+","-","*","/","%","min","max").contains(n)?"number":n.equals("cat")||n.equals("substr")?"string":Set.of("merge","map","filter").contains(n)?"array":"unknown";r.add(new JsonLogicOperatorDescriptor(n,null,min,max,type,"pure",false,"native","1.0"));}return List.copyOf(r);}
    private static final class Budget{private final JsonLogicLimits l;private int nodes,ops;Budget(JsonLogicLimits l){this.l=l;}void visit(int d){if(++nodes>l.maxNodes()||d>l.maxDepth())throw error(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,"Expression exceeds structural limits.","$",null);}void operation(){if(++ops>l.maxOperations())throw error(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,"Expression exceeds operation limit.","$",null);}void array(int n){if(n>l.maxArrayItems())throw error(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,"Array exceeds item limit.","$",null);}void string(int n){if(n>l.maxStringLength())throw error(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,"String exceeds length limit.","$",null);}}
}
