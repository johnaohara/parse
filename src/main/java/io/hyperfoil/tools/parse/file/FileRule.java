package io.hyperfoil.tools.parse.file;

import io.hyperfoil.tools.parse.factory.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import io.hyperfoil.tools.parse.Exp;
import io.hyperfoil.tools.parse.JsTransform;
import io.hyperfoil.tools.yaup.StringUtil;
import io.hyperfoil.tools.yaup.json.Json;
import io.hyperfoil.tools.yaup.json.JsonValidator;

import java.util.LinkedList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class FileRule {

    public static void main(String[] args) {
        JsonValidator validator = new JsonValidator(FileRule.getSchema());

        System.out.println(validator.getSchema().toString(2));

        System.exit(0);
        FileRule rule = FileRule.fromJson(Json.fromJs(
           "{" +
              "name: 'log.xml'," +
              "path: 'log.xml'," +
              "nest: 'faban.log'," +
              "asPath: function (path){\n" +
              "  function getTimestamp(js, selector){\n" +
              "    const found = Json.find(js,selector);\n" +
              "    if(found){\n" +
              "      if(found.isArray() && found.size() === 1){ found = found.getJson(0);}\n" +
              "      const value = found.getJson('millis',new Json()).getString('text()','');\n" +
              "      if(value.matches('\\d+')){return Java.type('java.lang.Long').parseLong(value);}\n" +
              "}\n" +
              "}\n" +
              "const rtrn = new Json();\n" +
              "const content = Xml.parseFile(path).toJson();\n" +
              "const first = getTimestamp(content,'$.log.record[0]');\n" +
              "if(first){Json.chainSet(rtrn,'timestamps.runStart',first);}\n" +
              "const rampUp = getTimestamp(content,'$.log.record[?(@.message['text()'] == 'Ramp up started')]');\n" +
              "if(rampUp){Json.chainSet(rtrn,'timestamps.rampUp',rampUp);}\n" +
              "const steadyState = getTimestamp(content,'$.log.record[?(@.message['text()'] == 'Ramp up completed')]');\n" +
              "if(steadyState){Json.chainSet(rtrn,'timestamps.steadyState',steadyState);}\n" +
              "const rampDown = getTimestamp(content,'$.log.record[?(@.message['text()'] == 'Steady state completed')]');\n" +
              "if(rampDown){Json.chainSet(rtrn,'timestamps.rampDown',rampDown);}\n" +
              "const runStop = getTimestamp(rtrn,'$.log.record[?(@.message['text()'] == 'Ramp down completed')]');\n" +
              "if(runStop){Json.chainSet(rtrn,'timestamps.runStop',runStop);}\n" +
              "const lastLog = getTimestamp(rtrn,'$.log.record[-1]');\n" +
              "if(lastLog){Json.chainSet(rtrn,'timestamps.lastLog',lastLog);}\n" +
              "const stats = Json.find(content,'$.log.record[?(@.class['text()'] == 'com.sun.faban.driver.engine.MasterImpl$StatsWriter')]');\n" +
              "console.log(stats)\n" +
              "}\n" +
              "}"));

        rule.apply("/home/wreicher/perfWork/eap73/2010/675/run/benchclient1.perf.lab.eng.rdu.redhat.com/specjdriverharness-jboss-eap-7-2-Beta.416Y/log.xml",(nest,json)->{
            System.out.println("nest: "+nest);
            System.out.println("json:\n"+json.toString(2));
        });
    }


    public static FileRule fromJson(Json json){
        FileRule rtrn = new FileRule(json.getString("name",""));
        if(json.has("nest")){
            rtrn.setNest(json.getString("nest"));
        }
        if(json.has("path")){
            rtrn.getCriteria().setPathPattern(json.getString("path"));
        }
        if(json.has("headerLines")){
            rtrn.getCriteria().setHeaderLines((int)json.getLong("headerLines"));
        }
        if(json.has("filters")){
            json.getJson("filters").values().stream().map(o->(Json)o).forEach(jsonFilter->{
                Filter f = Filter.fromJson(jsonFilter);
                if(f != null){
                    rtrn.addFilter(f);
                }else{
                    //TODO log error creating filter?
                }
            });
        }
        if(json.has("findHeader")){
            Object findHeader = json.get("findHeader");
            if(findHeader instanceof String){
                rtrn.getCriteria().addFindPattern(findHeader.toString());
            }else if (findHeader instanceof Json){
                Json findList = (Json)findHeader;
                findList.values().forEach(findIt->{
                    rtrn.getCriteria().addFindPattern(findIt.toString());
                });
                if(rtrn.getCriteria().getHeaderLines() < findList.size()){
                    rtrn.getCriteria().setHeaderLines(findList.size());
                }
            }else{
                //TODO alert error
            }
        }
        if(json.has("avoidHeader")){
            Object avoidheader = json.get("avoidHeader");
            if(avoidheader instanceof String){
                rtrn.getCriteria().addNotFindPattern(avoidheader.toString());
            }else if (avoidheader instanceof Json){
                Json avoidList = (Json)avoidheader;
                avoidList.values().forEach(avoidIt->{
                    rtrn.getCriteria().addNotFindPattern(avoidIt.toString());
                });
            }else{
                //TODO alert error
            }
        }
        if(json.has("asText")){
            TextConverter converter = new TextConverter();
            Object asText = json.get("asText");
            if(asText instanceof String){
                //TODO find the Factory
                converter.addFactory(()->{
                    switch (asText.toString().toLowerCase()){
                        case "csvfactory": return new CsvFactory().newParser();
                        case "dstatfactory": return new DstatFactory().newParser();
                        case "jep271factory": return new Jep271Factory().newParser();
                        case "jmaphistofactory": return new JmapHistoFactory().newParser();
                        case "jstackfactory": return new JStackFactory().newParser();
                        case "printgcfactory": return new PrintGcFactory().newParser();
                        case "serverlogfactory": return new ServerLogFactory().newParser();
                        case "substrategcfactory": return new SubstrateGcFactory().newParser();
                        case "xanfactory": return new XanFactory().newParser();
                        default:
                            throw new IllegalArgumentException("unknown factory "+asText.toString());
                    }
                });
            }else if (asText instanceof Json){
                Json expList = (Json)asText;
                expList.values().forEach(expData ->{
                    if(expData instanceof String){
                        Exp exp = new Exp(expData.toString());
                        converter.addExp(exp);
                    }else if (expData instanceof Json){
                        Exp exp = Exp.fromJson((Json)expData);
                        converter.addExp(exp);
                    }else{
                        //TODO alert error
                    }
                });
            }
        }
        if(json.has("asJbossCli")){
            Object asJbossCli = json.get("asJbossCli");
            if(asJbossCli instanceof String){
                String asString = asJbossCli.toString();
                if(asString.isEmpty()) {
                    rtrn.setConverter(new JbossCliConverter());
                }else{
                    rtrn.setConverter(new JbossCliConverter().andThen(new JsTransform(asString)));
                }
            }else {
                //TODO alert error
            }
        }
        if(json.has("asJson")){
            Object asJson = json.get("asJson");
            if(asJson instanceof String){
                String asString = (String)asJson;
                if(asString.isEmpty()){
                    rtrn.setConverter(new JsonConverter());
                }else{
                    rtrn.setConverter(new JsonConverter().andThen(new JsTransform(asString)));
                }
            }
        }
        if(json.has("asXml")){
            Object asXml = json.get("asXml");
            if(asXml instanceof String){
                String asString = (String)asXml;
                if(asString.isEmpty()){
                    rtrn.setConverter(new XmlConverter());
                }else{
                    rtrn.setConverter(new XmlConverter().andThen(new JsTransform(asString)));
                }
            }else if (asXml instanceof Json){
                XmlConverter converter = new XmlConverter();
                ((Json)asXml).values().stream().map(o->(Json)o).forEach(entry->{
                    Filter f = Filter.fromJson(entry);
                    if(f!=null) {
                        converter.addFilter(f);
                    }
                });
                rtrn.setConverter(converter);
            }
        }
        if(json.has("asPath")){
            rtrn.setConverter((path)->{
                try(Context context = Context.newBuilder("js").allowAllAccess(true).allowHostAccess(true).build()){
                    context.enter();
                    context.eval("js","function milliseconds(v){ return Packages.perf.yaup.StringUtil.parseKMG(v)};");
                    context.eval("js","const StringUtil = Packages.perf.yaup.StringUtil;");
                    context.eval("js","const Exp = Java.type('io.hyperfoil.tools.parse.Exp');");
                    context.eval("js","const ExpMerge = Java.type('io.hyperfoil.tools.parse.ExpMerge');");
                    context.eval("js","const MatchRange = Java.type('io.hyperfoil.tools.parse.MatchRange');");
                    context.eval("js","const Xml = Java.type('perf.yaup.xml.pojo.Xml');");
                    context.eval("js","const Json = Java.type('perf.yaup.json.Json');");
                    context.eval("js","const Eat = Java.type('io.hyperfoil.tools.parse.Eat');");
                    context.eval("js","const ValueType = Java.type('io.hyperfoil.tools.parse.ValueType')");
                    context.eval("js","const ValueMerge = Java.type('io.hyperfoil.tools.parse.ValueMerge');");
                    context.eval("js","const ExpRule = Java.type('io.hyperfoil.tools.parse.ExpRule')");
                    context.eval("js","");

                    context.eval("js","const console = {log: print}");

                    Value matcher = context.eval("js",json.getString("asPath"));
                    Value result = matcher.execute(path);
                    if(result.isHostObject()){
                        Object hostObj = result.asHostObject();
                        if(hostObj instanceof Json){
                            return (Json)hostObj;
                        }
                    }else if (result.hasMembers()){
                        //TODO convert value to Json
                    }
                }
                //TODO alert that failed to return from converter
                return new Json();
            });
        }
        return rtrn;
    }
    public static Json getSchema(){
        Json rtrn = new Json();
        rtrn.set("$schema","http://json-schema.org/draft-07/schema");
        rtrn.set("definitions",new Json());
        rtrn.getJson("definitions").set("filter",Filter.getSchemaDefinition());
        rtrn.getJson("definitions").set("rule",getSchemaDefinition("filter"));
        rtrn.getJson("definitions").set("exp",Exp.getSchemaDefinition("exp"));
        rtrn.set("$ref","#/definitions/rule");
        return rtrn;
    }
    public static Json getSchemaDefinition(String filterRef){
        return Json.fromJs("{" +
           "type: 'object'," +
           "properties: {" +
           "  name: {type: 'string'}," +
           "  nest: {type: 'string'}," +
           "  path: {type: 'string'}," +
           "  headerLines: {type: 'number'}," +
           "  filter: {type: 'array', items: { $ref: '#/definitions/"+filterRef+"' } }," +
           "  findHeader: { oneOf: [ {type: 'string'} , { type: 'array', items: { type: 'string' } } ] }," +
           "  avoidHeader: { oneOf: [ {type: 'string'} , { type: 'array', items: { type: 'string' } } ] }," +
           "  asText: {oneOf: [" +
           "    { type: 'string'}," +//supplier name or exp string
           "    { type: 'array', items: { $ref: '#/definitions/exp' } }," +
           "  ]}," +
           "  asJbossCli: {oneOf: [" +
           "    { type: 'string'}," +//empty string or javascript function (json)=>json
           "  ]}," +
           "  asJson: {oneOf: [" +
           "    { type: 'string'}," +//empty string or javascript function (json)=>json
           "  ]}," +
           "  asXml: {oneOf: [" +
           "    { type: 'string'}," +
           "    { type: 'array', items: { $ref: '#/definitions/filter'} }," +
           "  ]}," +
           "  asPath: {type: 'string'}," +
           "}," +
           "oneOf: [" +
           "  { required: ['asText'] }," +
           "  { required: ['asJbossCli'] }," +
           "  { required: ['asJson'] }," +
           "  { required: ['asXml'] }," +
           "  { required: ['asPath'] }," +
           "]," +
           "additionalProperties: false" +
           "}");
    }


    private String name;
    private MatchCriteria criteria = new MatchCriteria();
    private final List<Filter> filters = new LinkedList<>();
    private String nest="";
    private Function<String, Json> converter =null;

    public FileRule(){this("");}
    public FileRule(String name){
        this.name = name;
    }

    public void setName(String name){this.name = name;}
    public String getName(){return name;}

    public boolean isValid(){
        return converter !=null && nest!=null;
    }
    public FileRule setCriteria(MatchCriteria criteria){
        this.criteria=criteria;
        return this;
    }
    public FileRule withCriteria(Consumer<MatchCriteria> consumer){
        consumer.accept(this.criteria);
        return this;
    }
    public FileRule addFilter(Filter filter){
        this.filters.add(filter);
        return this;
    }
    public FileRule accept(String acceptPattern){
        //TODO create a filter that accepts json that match
        return this;
    }
    public FileRule block(String pattern){
        //TODO create a filter aht acceps json that do NOT match
        return this;
    }

    public String getNest(){return nest;}
    public FileRule setNest(String nest){
        this.nest = nest;
        return this;
    }

    public List<Filter> getFilters(){return filters;}
    public MatchCriteria getCriteria(){return criteria;}
    public Function<String,Json> getConverter(){return converter;}
    public FileRule setConverter(Function<String,Json> converter){
        this.converter = converter;
        return this;
    }

    public boolean apply(String path, BiConsumer<String,Json> callback){
        try {
            Json state = new Json();
            boolean matched = getCriteria().match(path, state);

            if (matched) {

                Json result = getConverter().apply(path);
                String nestPath = StringUtil.populatePattern(getNest(), Json.toObjectMap(state));
                if (getFilters().isEmpty()) {
                    callback.accept(nestPath, result);
                } else {
                    final Json postFilter = new Json();
                    BiConsumer<String, Object> filterCallback = (nest, json) -> {
                        Json.chainSet(postFilter, nest, json);
                    };
                    for (Filter filter : getFilters()) {
                        filter.apply(result, filterCallback);
                    }
                    callback.accept(nestPath, postFilter);
                }
            }
            return matched;
        }catch(Exception e){
            System.out.println("Failed to convert "+path);
            e.printStackTrace(System.out);
        }
        return false;
    }
}