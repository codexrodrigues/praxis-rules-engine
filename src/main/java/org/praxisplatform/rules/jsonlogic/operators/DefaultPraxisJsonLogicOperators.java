package org.praxisplatform.rules.jsonlogic.operators;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.internal.MissingValue;
import org.praxisplatform.rules.jsonlogic.internal.SafeRegex;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationContext;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode;

/** Canonical Praxis-owned operator definitions. */
public final class DefaultPraxisJsonLogicOperators {
    private DefaultPraxisJsonLogicOperators() { }

    /**
     * Creates the canonical built-in Praxis extension set.
     * @return deterministic collection of platform-owned operator implementations
     */
    public static Collection<PraxisJsonLogicOperator> all() {
        List<PraxisJsonLogicOperator> out = new ArrayList<>();
        out.add(op("contains",2,2,"boolean","pure",(a,c,e)-> {
            Object x=a.get(0), y=a.get(1); if (nullish(x)) return false;
            if (x instanceof String s && y instanceof String p) return s.contains(p);
            if (x instanceof List<?> l) return l.stream().anyMatch(v->e.equalsStrict(v,y));
            throw type("contains","requires a string or array as the first argument"); }));
        out.add(op("startsWith",2,2,"boolean","pure",(a,c,e)-> stringOrFalse(a,"startsWith",String::startsWith)));
        out.add(op("endsWith",2,2,"boolean","pure",(a,c,e)-> stringOrFalse(a,"endsWith",String::endsWith)));
        out.add(op("matches",2,2,"boolean","pure",(a,c,e)-> {
            if (nullish(a.get(0))) return false; if (!(a.get(0) instanceof String s) || !(a.get(1) instanceof String p)) throw type("matches","requires string arguments");
            return SafeRegex.compile(p,c.limits()).matcher(s).find(); }));
        out.add(op("isBlank",1,1,"boolean","pure",(a,c,e)-> {Object v=a.get(0); return nullish(v)||(v instanceof String s&&s.isBlank())||(v instanceof List<?> l&&l.isEmpty());}));
        out.add(op("len",1,1,"number","pure",(a,c,e)-> {Object v=a.get(0); if(nullish(v))return null; if(v instanceof String s)return s.length(); if(v instanceof List<?> l)return l.size(); if(v instanceof Map<?,?>m)return m.size(); throw type("len","requires a string, array, or object");}));
        out.add(numberUnary("round", n->n.setScale(0,java.math.RoundingMode.HALF_UP)));
        out.add(numberUnary("ceil", n->n.setScale(0,java.math.RoundingMode.CEILING)));
        out.add(numberUnary("floor", n->n.setScale(0,java.math.RoundingMode.FLOOR)));
        out.add(numberUnary("abs", BigDecimal::abs));
        out.add(op("coalesce",1,null,"unknown","pure",(a,c,e)->a.stream().filter(v->!nullish(v)&&!"".equals(v)).findFirst().orElse(null)));
        out.add(op("now",0,0,"number","contextual",(a,c,e)-> requiredNow(c).toEpochMilli()));
        out.add(op("date",1,1,"number","pure",(a,c,e)-> temporal(a.get(0),c).toEpochMilli()));
        out.add(since("yearsSince",365.25d)); out.add(since("monthsSince",30.4375d)); out.add(since("daysSince",1d));
        out.add(op("toNumber",1,1,"number","pure",(a,c,e)-> {try{return new BigDecimal(String.valueOf(a.get(0)));}catch(Exception ex){return null;}}));
        out.add(op("stringify",1,1,"string","pure",(a,c,e)-> nullish(a.get(0))?"":e.stringify(a.get(0))));
        out.add(op("jsonGet",2,2,"unknown","pure",(a,c,e)-> {if(!(a.get(0) instanceof Map<?,?>m)||!(a.get(1) instanceof String p))throw type("jsonGet","requires an object and a string path");return e.readPathFromObject(m,p);}));
        out.add(op("hasKey",2,2,"boolean","pure",(a,c,e)-> {if(!(a.get(0) instanceof Map<?,?>m)||!(a.get(1) instanceof String p))throw type("hasKey","requires an object and a string path");return e.hasPathInObject(m,p);}));
        out.add(op("isToday",1,1,"boolean","contextual",(a,c,e)-> localDate(a.get(0),c).equals(requiredNow(c).atZone(zone(c)).toLocalDate())));
        out.add(op("inLast",3,3,"boolean","contextual",(a,c,e)-> {LocalDate value=localDate(a.get(0),c), now=requiredNow(c).atZone(zone(c)).toLocalDate(); long n=decimal(a.get(1),"inLast").abs().longValue(); String u=String.valueOf(a.get(2)).toLowerCase(); LocalDate floor=u.startsWith("week")?now.minusWeeks(n):u.startsWith("month")?now.minusMonths(n):u.startsWith("day")?now.minusDays(n):throwTemporal("Unsupported inLast unit");return !value.isBefore(floor)&&!value.isAfter(now);}));
        out.add(op("weekdayIn",2,2,"boolean","contextual",(a,c,e)-> {LocalDate d=localDate(a.get(0),c); if(!(a.get(1) instanceof List<?>days))throw type("weekdayIn","requires an array of weekdays");return days.stream().map(v->decimal(v,"weekdayIn").intValue()).anyMatch(n->n==d.getDayOfWeek().getValue());}));
        return List.copyOf(out);
    }

    private static PraxisJsonLogicOperator numberUnary(String name, java.util.function.UnaryOperator<BigDecimal> fn){return op(name,1,1,"number","pure",(a,c,e)->fn.apply(decimal(a.get(0),name)));}
    private static PraxisJsonLogicOperator since(String name,double days){return op(name,1,1,"number","contextual",(a,c,e)->(long)Math.floor(ChronoUnit.MILLIS.between(temporal(a.get(0),c),requiredNow(c))/(86400000d*days)));}
    private static boolean stringOrFalse(List<Object>a,String op,java.util.function.BiPredicate<String,String> fn){if(nullish(a.get(0)))return false;if(a.get(0)instanceof String x&&a.get(1)instanceof String y)return fn.test(x,y);throw type(op,"requires string arguments");}
    private static boolean nullish(Object v){return v==null||v==MissingValue.INSTANCE;}
    private static BigDecimal decimal(Object v,String op){if(v instanceof BigDecimal b)return b;if(v instanceof Number n)try{return new BigDecimal(n.toString());}catch(Exception ignored){}throw type(op,"requires finite numeric arguments");}
    private static Instant requiredNow(JsonLogicEvaluationContext c){if(c.nowUtc()==null)throw temporalError("nowUtc is required for contextual operators");try{return Instant.parse(c.nowUtc());}catch(Exception e){throw temporalError("nowUtc must be an ISO-8601 instant");}}
    private static ZoneId zone(JsonLogicEvaluationContext c){if(c.userTimeZone()==null)throw temporalError("userTimeZone is required for contextual operators");try{return ZoneId.of(c.userTimeZone());}catch(Exception e){throw temporalError("userTimeZone is invalid");}}
    private static Instant temporal(Object v,JsonLogicEvaluationContext c){try{if(v instanceof Number n)return Instant.ofEpochMilli(n.longValue());String s=String.valueOf(v);if(s.matches("\\d{4}-\\d{2}-\\d{2}"))return LocalDate.parse(s).atStartOfDay(ZoneId.of("UTC")).toInstant();if(!s.matches(".*(Z|[+-]\\d{2}:\\d{2})$"))throw temporalError("date-time values require an explicit offset");return Instant.parse(s);}catch(PraxisJsonLogicException e){throw e;}catch(DateTimeParseException e){throw temporalError("temporal input is invalid");}}
    private static LocalDate localDate(Object v,JsonLogicEvaluationContext c){if(v instanceof String s&&s.matches("\\d{4}-\\d{2}-\\d{2}"))try{return LocalDate.parse(s);}catch(Exception e){throw temporalError("date input is invalid");}return temporal(v,c).atZone(zone(c)).toLocalDate();}
    private static <T> T throwTemporal(String message){throw temporalError(message);}
    private static PraxisJsonLogicException temporalError(String m){return new PraxisJsonLogicException(JsonLogicIssueCode.RULE_TEMPORAL_INPUT_INVALID,m+".","$",null);}
    private static PraxisJsonLogicException type(String op,String m){return new PraxisJsonLogicException(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,"`"+op+"` "+m+".","$",op);}
    private static PraxisJsonLogicOperator op(String name,int min,Integer max,String type,String purity,Body body){return new PraxisJsonLogicOperator(){private final JsonLogicOperatorDescriptor descriptor=new JsonLogicOperatorDescriptor(name,"praxis",min,max,type,purity,false,"praxis","1.0");public JsonLogicOperatorDescriptor descriptor(){return descriptor;}public Object evaluate(List<Object>a,JsonLogicEvaluationContext c,PraxisJsonLogicEngine e){return body.apply(a,c,e);}};}
    @FunctionalInterface private interface Body{Object apply(List<Object>a,JsonLogicEvaluationContext c,PraxisJsonLogicEngine e);}
}
