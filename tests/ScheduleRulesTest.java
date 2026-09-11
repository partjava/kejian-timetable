import com.kejian.app.ScheduleRules;
import java.util.*;

public class ScheduleRulesTest {
    static int checks=0;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void rejects(String input,int max){try{ScheduleRules.parseWeeks(input,max);throw new AssertionError("accepted: "+input);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        List<Integer> actual=ScheduleRules.parseWeeks("第1–3周，5周",20);
        check(actual.equals(Arrays.asList(1,2,3,5)),"gap preserved");
        check(ScheduleRules.formatWeeks(actual).equals("1-3,5"),"week roundtrip");
        check(ScheduleRules.parseWeeks("3,1-3,5",20).equals(actual),"sorted deduplication");
        rejects("0-3",20);rejects("5-2",20);rejects("1-21",20);rejects("1,,3",20);rejects("",20);rejects("a",20);rejects("1-2-3",20);
        check(!ScheduleRules.overlap(1,1,2,Arrays.asList(1,2),1,1,2,Arrays.asList(3,4)),"same time different weeks allowed");
        check(!ScheduleRules.overlap(1,1,2,actual,1,3,4,actual),"adjacent periods allowed");
        check(ScheduleRules.overlap(6,1,3,actual,6,3,4,actual),"Saturday overlap detected");
        check(!ScheduleRules.overlap(6,1,3,actual,7,1,3,actual),"weekend days distinct");
        System.out.println(checks+" schedule rule checks passed");
    }
}
