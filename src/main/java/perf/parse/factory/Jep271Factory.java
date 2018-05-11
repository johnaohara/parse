package perf.parse.factory;

import perf.parse.*;

public class Jep271Factory {
    public Parser newParser() {
        Parser p = new Parser();
        addToParser(p);
        return p;
    }
    public void addToParser(Parser p) {
        addToParser(p,false);
    }
    public void addToParser(Parser p,boolean strict){
        //thankfully level is always the 2nd to last decorator

        p.add(gcId()
                .add(gcExpanding())//does not occur under GC(#) but that might be a bug
                .add(gcShrinking())

                .add(gcPause())

                .add(parallelSizeChanged())//ParallelGC

                .add(g1ResizePhase().requires("gc-g1").set(Merge.Entry))//G1
                .add(g1TimedPhase().requires("gc-g1").set(Merge.Entry))//G1

//                .add(g1FinalizeLiveData().requires("gc-g1"))//G1
//                .add(g1CycleTime().requires("gc-g1"))//G1
//                .add(g1ClearBitmap().requires("gc-g1"))//G1
//                .add(g1ClearLive().requires("gc-g1"))//G1

                //gc+cpu
                .add(gcCpu())

                //gc+age
                .add(gcAge())
                .add(gcAgeTableHeader().set(Rule.PushTarget))//G1
                .add(gcAgeTableEntry())//G1



                .add(gcHeapHeader().set(Rule.PreClearTarget).set(Rule.PushTarget)
                        .add(gcHeapRegion())//oracle-10 puts it on the same line as "Heap (before|after)..."
                        .add(gcHeapRegionG1().requires("gc-g1").set(Rule.PushTarget))
                )
                .add(gcHeapRegion())
                .add(gcHeapSpace())
                .add(gcHeapSpaceG1().requires("gc-g1"))
                .add(gcHeapMetaRegion())
                .add(gcHeapMetaSpace())
                .add(gcHeapRegionResize().set(Rule.PreClearTarget))
                .add(gcHeapRegionResizeG1().requires("gc-g1").set(Rule.PreClearTarget).set(Rule.PushTarget))
                .add(gcHeapRegionResizeG1UsedWaste().requires("gc-g1"))

                .add(gcClassHistoStart())
                .add(gcClassHistoEntry())
                .add(gcClassHistoTotal())
                .add(gcClassHistoEnd())
        );

        p.add(gcLevel().set(Rule.ChildrenLookBehind).enables("jep271-decorator")
                .add(time())
                .add(utcTime())
                .add(uptime())
                .add(timeMillis())
                .add(uptimeMillis())
                .add(timeNanos())
                .add(uptimeNanos())
        );

        //moved to after gcId to avoid appending to previous event
        //
        p.add(
            //need requires so it won't match [GC ... from printGc logs
            gcTags().requires("jep271-decorator")
        );//always the last decorator

        //p.add(gcKeyValue());
        p.add(g1MarkStack().requires("gc-g1"));

        p.add(usingSerial());
        p.add(usingParallel());
        p.add(usingCms());
        p.add(usingG1());

        p.add(gcExpanding());//included here to match output from openjdk10+46

        p.add(safepointStopTime());//safepoint
        p.add(safepointAppTime());//safepoint

        //gc+heap
        p.add(gcHeapSize());
        p.add(gcHeapRange());
        p.add(gcHeapYoungRange());
        p.add(gcHeapOldRange());
        //
        //end moved to after gcId
    }

    //enables different gc collectors
    //
    public Exp usingCms(){ //Using Concurrent Mark Sweep
        return new Exp("using","Using (?<gc>Concurrent Mark Sweep)")
                .enables("gc-cms");
    }
    public Exp usingSerial(){ //Using Serial
        return new Exp("using","Using (?<gc>Serial)")
                .enables("gc-serial");
    }
    public Exp usingParallel(){ //Using Parallel
        return new Exp("using","Using (?<gc>Parallel)")
                .enables("gc-parallel");
    }
    public Exp usingG1(){ //Using G1
        return new Exp("using","Using (?<gc>G1)")
                .enables("gc-g1");
    }

    //gc
    public Exp gcPause(){//"Pause Young (Allocation Failure) 62M->15M(241M) 9.238ms"
        return new Exp("pause","Pause (?<region>.+\\S)\\s+\\((?<reason>[^\\)]+)\\)")
                .add(gcResize())
                .add(new Exp("time","(?<milliseconds>\\d+\\.\\d{3})ms"));
    }

    public Exp gcTags(){
        return new Exp("tags","\\[(?<tags:set>[^\\s,\\]]+)")
                .set(Rule.TargetRoot)
                .add(new Exp("otherTags","^,(?<tags:set>[^\\s,\\]]+)")
                        .set(Rule.Repeat)
                        .set(Rule.TargetRoot)
                )
                .add(new Exp("tagsEnd","\\s*\\]")
                )

                ;
    }
    public Exp gcResize(){//61852K->15323K(247488K)
        return new Exp("gcResize","(?<usedBefore:KMG>\\d+[bBkKmMgG])->(?<usedAfter:KMG>\\d+[bBkKmMgG])\\((?<capacity:KMG>\\d+[bBkKmMgG])\\)");
    }
    public Exp gcLevel(){//"[info ]"
        return new Exp("level","\\[(?<level:last>error|warning|info|debug|trace|develop)\\s*\\]")
                .enables("jep271")
                .set(Rule.TargetRoot)
                .eat(Eat.ToMatch);
    }

    public Exp gcKeyValue(){//TODO when is this used
        return new Exp("gcKeyValue","(?<key>\\S+): (?<value>\\d+)")
                .group("stat")
                .set(Merge.Entry);
    }

    //Parallel
    //
    public Exp parallelSizeChanged(){//"PSYoung generation size changed: 1358848K->1356800K"
        return new Exp("parallelSizeChange","(?<region>\\w+) generation size changed: (?<before:KMG>\\d+[bBkKmMgG])->(?<after:KMG>\\d+[bBkKmMgG])")
                .group("resize");
    }

    //G1GC
    //
    public Exp g1MarkStack(){//"MarkStackSize: 4096k  MarkStackSizeMax: 524288k"
        return new Exp("gcG1MarkStack","MarkStackSize: (?<size:KMG>\\d+[bBkKmMgG])\\s+MarkStackSizeMax: (?<max:KMG>\\d+[bBkKmMgG])")

                .group("markStack");
    }
    public Exp g1ResizePhase(){//"Pause Remark 40M->40M(250M) 1.611ms"
        return new Exp("g1ResizePhase","(?<phase>\\w+(?:\\s\\w+)*) (?<before:KMG>\\d+[bBkKmMgG])->(?<after:KMG>\\d+[bBkKmMgG])\\((?<capacity:KMG>\\d+[bBkKmMgG])\\) (?<milliseconds>\\d+\\.\\d{3})ms")
                .group("phases")
                ;
    }
    public Exp g1TimedPhase(){//"Finalize Live Data 0.000ms"
        return new Exp("g1TimedPhase","(?<phase>\\w+(?:\\s\\w+)*) (?<milliseconds>\\d+\\.\\d{3})ms")
                .group("phases")
                ;
    }

    //gc+cpu
    public Exp gcCpu(){//"User=0.02s Sys=0.01s Real=0.02s"
        return new Exp("gcCpu","User=(?<user>\\d+\\.\\d{2,3})s Sys=(?<sys>\\d+\\.\\d{2,3})s Real=(?<real>\\d+\\.\\d{2,3})s")
                .group("cpu")
                .eat(Eat.Line)
                ;
    }

    //gc+heap=trace
    public Exp gcHeapSize(){//"Maximum heap size 4173353984"
        return new Exp("gcHeapSize","(?<limit>Initial|Minimum|Maximum) heap size (?<size>\\d+)")
                .group("heap")
                .set("limit","size")
                //.key("limit")
                ;
    }
    //gc+heap=debug
    public Exp gcHeapRange(){//"Minimum heap 8388608  Initial heap 262144000  Maximum heap 4175429632"
        return new Exp("gcHeapRange","Minimum heap (?<min>\\d+)\\s+Initial heap (?<initial>\\d+)\\s+Maximum heap (?<max>\\d+)")
                .group("heap");
    }
    //gc+heap=trace
    public Exp gcHeapYoungRange(){//"1: Minimum young 196608  Initial young 87359488  Maximum young 1391788032"
        return new Exp("gcHeapYoungRange","1: Minimum young (?<min>\\d+)\\s+Initial young (?<initial>\\d+)\\s+Maximum young (?<max>\\d+)")
                .group("heap")
                .group("young");
    }

    public Exp gcHeapOldRange(){//"Minimum old 65536  Initial old 174784512  Maximum old 2783641600"
        return new Exp("gcHeapOldRange","Minimum old (?<min>\\d+)\\s+Initial old (?<initial>\\d+)\\s+Maximum old (?<max>\\d+)")
                .group("heap")
                .group("old");
    }

    //gc+heap=trace
    public Exp gcHeapHeader(){//"Heap before GC invocations=0 (full 0): "
        return new Exp("gcHeapHeader","Heap (?<phase>before|after) GC invocations=(?<invocations>\\d+) \\(full (?<full>\\d+)\\):\\s*")
                .group("heap")
                .key("phase")
                ;
    }
    public Exp gcHeapRegion(){//"def new generation   total 76800K, used 63648K [0x00000006c7200000, 0x00000006cc550000, 0x000000071a150000)"
        return new Exp("gcHeapRegion","(?<region:nestLength>\\s+)(?<name>\\w+(?:\\s\\w+)*)\\s+total (?<total:KMG>\\d+[bBkKmMgG]), used (?<used:KMG>\\d+[bBkKmMgG])" +
                "\\s+\\[(?<start>[^,]+), (?<current>[^,]+), (?<end>[^(]+)\\)")
                //.group("region")
                //.key("region")
                ;
    }
    //TODO NOT in gc.json
    public Exp gcHeapRegionG1(){
        return new Exp("gcHeapRegion_g1","(?<region:nestLength>\\s+)(?<name>\\w+(?:\\s\\w+)*)\\s+total (?<total:KMG>\\d+[bBkKmMgG]), used (?<used:KMG>\\d+[bBkKmMgG])"+
                "\\s+\\[(?<start>[^,]+), (?<end>[^(]+)\\)")
                ;
    }
    public Exp gcHeapMetaRegion(){//"Metaspace       used 4769K, capacity 4862K, committed 5120K, reserved 1056768K"
        return new Exp("gcHeapMetaRegion","(?<region:nestLength>\\s+)(?<name>Metaspace)" +
                "\\s+used (?<used:KMG>\\d+[KMG]), capacity (?<capacity:KMG>\\d+[bBkKmMgG]), committed (?<committed:KMG>\\d+[bBkKmMgG]), reserved (?<reserved:KMG>\\d+[bBkKmMgG])")
                ;
    }

    public Exp gcHeapRegionResize(){//"ParOldGen: 145286K->185222K(210944K)"
        return new Exp("gcHeapRegionResize","\\s*(?<region>\\w+): (?<before:KMG>\\d+[bBkKmMgG])->(?<after:KMG>\\d+[bBkKmMgG])\\((?<size:KMG>\\d+[bBkKmMgG])\\)")
                .key("region")
                ;
    }
    public Exp gcHeapRegionResizeG1(){//"Eden regions: 4->0(149)"
        return new Exp("gcHeapRegionResizeG1","(?<region>\\S+) regions: (?<before>\\d+)->(?<after>\\d+)")
                .key("region")

                .add(new Exp("gcHeapRegionRegizeG1_total","\\((?<total>\\d+)\\)"))
                ;
    }
    public Exp gcHeapRegionResizeG1UsedWaste(){//" Used: 20480K, Waste: 0K"
        return new Exp("gcHeapRegionG1UsedWaste","Used: (?<used:KMG>\\d+[bBkKmMgG]), Waste: (?<waste:KMG>\\d+[bBkKmMgG])")
                ;
    }
    public Exp gcHeapMetaSpace(){//"  class space    used 388K, capacity 390K, committed 512K, reserved 1048576K"
        return new Exp("gcHeapMetaSpace","\\s*(?<space>\\S+) space"+
                "\\s+used (?<used:KMG>\\d+[bBkKmMgG]), capacity (?<capacity:KMG>\\d+[bBkKmMgG]), committed (?<committed:KMG>\\d+[bBkKmMgG]), reserved (?<reserved:KMG>\\d+[bBkKmMgG])")
                .key("space");
    }
    public Exp gcHeapSpace(){//"   eden space 68288K,  93% used [0x00000006c7200000, 0x00000006cb076880, 0x00000006cb4b0000)"
        return new Exp("gcHeapSpace","\\s+(?<space>\\S+) space (?<size:KMG>\\d+[bBkKmMgG]),\\s+(?<used>\\d+)% used"+
                "\\s+\\[(?<start>[^,]+),\\s?(?<current>[^,]+),\\s?(?<end>[^(]+)\\)")
                .key("space")
                ;
    }
    public Exp gcHeapSpaceG1(){//"   region size 1024K, 5 young (5120K), 0 survivors (0K)"
        return new Exp("gcHeapSpaceG1","region size (?<regionSize:KMG>\\d+[bBkKmMgG]), (?<youngCount>\\d+) young \\((?<youngSize:KMG>\\d+[bBkKmMgG])\\), (?<survivorCount>\\d+) survivors \\((?<survivorSize:KMG>\\d+[bBkKmMgG])\\)")
                ;
    }


    //TODO gc,safepoint with -XX:+UseG1GC injects safepoint between GC(#) lines
    //this means it would have to be part of the gc event (sum?) or a separate parser
    //using Sum for the time being

    //safepoint=info
    public Exp safepointStopTime(){//"Total time for which application threads were stopped: 0.0019746 seconds, Stopping threads took: 0.0000102 seconds"
        return new Exp("safepointStop","Total time for which application threads were stopped: (?<stoppedSeconds:sum>\\d+\\.\\d+) seconds, Stopping threads took: (?<quiesceSeconds:sum>\\d+\\.\\d+) seconds")
                .group("safepoint")
                ;
    }
    //safepoint=info
    public Exp safepointAppTime(){//"Application time: 0.0009972 seconds"
        return new Exp("safepointApplication","Application time: (?<applicationSeconds:sum>\\d+\\.\\d+) seconds")
                .group("safepoint")
                ;
    }
    public Exp gcClassHistoStart(){//"Class Histogram (before full gc)"
        return new Exp("gcClassHistoStart","Class Histogram \\((?<phase>\\S+) full gc\\)")
                ;
    }
    public Exp gcClassHistoEntry(){//"    1:          2709     1963112296  [B (java.base@10)"
        return new Exp("gcClassHistoEntry","(?<num>\\d+):\\s+(?<count>\\d+):?\\s+(?<bytes>\\d+)\\s+(?<name>.*)")
                .group("histo")
                .set(Merge.Entry)
                ;
    }
    public Exp gcClassHistoTotal(){//"Total         14175     1963663064"
        return new Exp("gcClassHistoTotal","Total\\s+(?<count>\\d+)\\s+(?<bytes>\\d+)")
                .group("total")
                ;
    }
    public Exp gcClassHistoEnd(){//"Class Histogram (before full gc) 18.000ms"
        return new Exp("gcClassHistoEnd","Class Histogram \\((?<phase>\\S+) full gc\\) (?<milliseconds>\\d+\\.\\d{3})ms")
                ;

    }
    public Exp gcId(){//"GC(27)"
        return new Exp("gcId","GC\\((?<gcId>\\d+)\\)")
                .set(Rule.TargetRoot)
                .set("gcId",Value.TargetId)
                ;
    }

    //Decorators
    //
    public Exp time(){ //[2018-04-12T09:24:30.397-0500]
        return new Exp("time","\\[(?<time:first>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}-\\d{4})\\]")
                .set(Rule.TargetRoot)
                ;
    }
    public Exp utcTime(){ //[2018-04-12T14:24:30.397+0000]
        return new Exp("utcTime","\\[(?<utcTime:first>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+\\d{4})\\]")
                .set(Rule.TargetRoot)
                ;
    }
    public Exp uptime(){ //[0.179s]
        return new Exp("uptime","\\[(?<uptime:first>\\d+\\.\\d{3})s\\]")
                .set(Rule.TargetRoot)
                ;
    }
    public Exp timeMillis(){ //[1523543070397ms]
        return new Exp("timeMillis","\\[(?<timeMillis:first>\\d{13})ms\\]")
                .set(Rule.TargetRoot)
                ;
    }
    public Exp uptimeMillis(){ //[15ms]
        return new Exp("uptimeMillis","\\[(?<uptimeMillis:first>\\d{1,12})ms\\]")
                .set(Rule.TargetRoot)
                ;
    }
    public Exp timeNanos(){ //[6267442276019ns]
        return new Exp("timeNanos","\\[(?<timeNanos:first>\\d{13,})ns\\]")
                .set(Rule.TargetRoot)
                ;
    }
    public Exp uptimeNanos(){ //[10192976ns]
        return new Exp("uptimeNanos","\\[(?<uptimeNanos:first>\\d{1,12})ns\\]")
                .set(Rule.TargetRoot)
                ;
    }
    //TODO hostname,pid,tid,

    public Exp gcExpanding(){//"Expanding tenured generation from 170688K by 39936K to 210624K"
        return new Exp("expand","Expanding (?<region>\\S.+\\S) from (?<from:KMG>\\d+[kKmMgG]?)")
                .add(new Exp("by"," by (?<by:KMG>\\d+[kKmMgG]?)"))
                .add(new Exp("to"," to (?<to:KMG>\\d+[kKmMgG]?)"))
                ;
    }
    public Exp gcShrinking(){//"Shrinking ParOldGen from 171008K by 11264K to 159744K"
        return new Exp("shrink","Shrinking (?<region>\\S.+\\S) from (?<from:KMG>\\d+[kKmMgG]?)")
                .add(new Exp("by"," by (?<by:KMG>\\d+[kKmMgG]?)"))
                .add(new Exp("to"," to (?<to:KMG>\\d+[kKmMgG]?)"))
                ;
    }

    //gc+age
    //
    public Exp gcAge(){//"Desired survivor size 4358144 bytes, new threshold 1 (max threshold 6)"
        return new Exp("gcAge","Desired survivor size (?<survivorSize>\\d+) bytes, new threshold (?<threshold>\\d+) \\(max threshold (?<maxThreshold>\\d+)\\)")
                ;
    }
    public Exp gcAgeTableHeader(){//"Age table with threshold 1 (max threshold 6)"
        return new Exp("gcAgeTableHeader","Age table with threshold (?<tableThreshold>\\d+) \\(max threshold (?<tableMaxThreshold>\\d+)\\)")
                ;
    }
    public Exp gcAgeTableEntry(){//"- age   1:    6081448 bytes,    6081448 total"
        return new Exp("gcAgeTableEntry","- age\\s+(?<age>\\d+):\\s+(?<size>\\d+) bytes,\\s+(?<total>\\d+) total")
                .group("table")
                .key("age");
    }



}