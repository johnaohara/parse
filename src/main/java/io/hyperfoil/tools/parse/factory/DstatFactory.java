package io.hyperfoil.tools.parse.factory;

import io.hyperfoil.tools.parse.Eat;
import io.hyperfoil.tools.parse.ExpRule;
import io.hyperfoil.tools.parse.Parser;
import io.hyperfoil.tools.parse.Exp;
import io.hyperfoil.tools.yaup.json.Json;

import java.util.ArrayList;

/**
 * Created by wreicher
 * DstatFactory - creates a Parser for dstat logs
 */
public class DstatFactory implements ParseFactory{

    public Exp defaultMessage(){
        return new Exp("defaultMessage","You did not select any stats, using -cdngy by default");
    }
    public Exp headerGroup(){
        return new Exp("headerGroup","[ ]?[\\-]{1,}(?<header>[^ \\-]+(:?[\\-\\/]?[^ \\-\\/]+)*)[\\- ]{1}");
    }
    public Exp columnGroup(){
        return new Exp("columnGroup","\\s*(?<column>[\\:\\|]|[^\\s\\:\\|]+)");
    }
    public Parser newParser(){
        Parser p = new Parser();
        addToParser(p);
        return p;
    }
    public void addToParser(Parser p){
        final ArrayList<String> headers = new ArrayList<>();
        p.add(
            defaultMessage()
           .addRule(ExpRule.PreClose)
            .eat(Eat.Line)
        );
        p.add(
            headerGroup()
            .addRule(ExpRule.PreClose)
            .addRule(ExpRule.Repeat)
            .eat(Eat.Line)
            .execute((line, match, pattern, parser) -> {
                Json arry = match.getJson("header");
                for(int i=0; i<arry.size(); i++){
                    String header = arry.getString(i);
                    if(header.matches(".*[_\\-/].*")){
                        StringBuilder fixHeader = new StringBuilder(header.length());
                        for(int c=0; c<header.length(); c++){
                            char l = header.charAt(c);
                            if( "_/-".indexOf(l)>-1){
                                c++;
                                if(c<header.length()){
                                    fixHeader.append(Character.toUpperCase(header.charAt(c)));
                                }
                            }else{
                                fixHeader.append(l);
                            }
                        }
                        headers.add(fixHeader.toString());
                    }else{
                        headers.add(header);
                    }

                }
            })
        );
        p.add(
            columnGroup()
            .addRule(ExpRule.PreClose)
            .addRule(ExpRule.Repeat)
            .eat(Eat.Line)
            .execute((line, match, pattern, parser) -> {
                Json arry = match.getJson("column");
                StringBuilder sb = new StringBuilder();
                sb.append("\\s*");
                int h = 0;
                for(int i=0; i<arry.size(); i++){
                    String column = arry.getString(i);
                    if("|".equals(column) || ":".equals(column)){
                        sb.append("[:\\|]?");
                        h++;
                    } else {
                        String header = headers.get(h);
                        sb.append("(?<");
                        sb.append(header.replaceAll("[_\\-/]", ""));
                        sb.append("\\.");
                        sb.append(column.replaceAll("[_\\-/]", ""));
                        sb.append(":KMG>");
                        if ("time".equals(column)) {
                            sb.append("\\d{1,2}\\-\\d{1,2} \\d{2}:\\d{2}:\\d{2}");
                        } else {
                            sb.append("\\-|\\d+\\.?\\d*[KkMmGgBb]?");
                        }
                        sb.append(")");
                    }
                    sb.append("\\s*");
                }
                Exp entryExp = new Exp("dstat",sb.toString());
                entryExp.addRule(ExpRule.PreClose);
                entryExp.eat(Eat.Line);
                parser.addAhead(entryExp);
            })

        );
    }
}
