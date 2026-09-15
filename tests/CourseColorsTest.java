import com.kejian.app.CourseColors;
import java.util.*;

public class CourseColorsTest {
    static int checks=0;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}

    public static void main(String[] args){
        // The suggested list is interpolated into the model prompt, so its size is part of the AI
        // contract rather than a free choice. It is no longer the seed pool.
        check(CourseColors.SUGGESTED.length==6,"suggested list stays at six");

        Set<String> palette=new LinkedHashSet<>();
        for(String hex:CourseColors.PALETTE){
            check(CourseColors.valid(hex),"palette entry is #RRGGBB: "+hex);
            check(palette.add(hex),"palette entry is unique: "+hex);
        }
        check(palette.size()==CourseColors.PALETTE.length,"palette has no duplicates");
        check(CourseColors.PALETTE.length==30,"palette offers thirty swatches");

        // Every colour a course can already be wearing must be findable in the picker, otherwise
        // the editor would show "no swatch selected" for an existing course.
        for(String hex:CourseColors.SUGGESTED)
            check(palette.contains(hex),"seed colour is on the palette: "+hex);
        check(palette.contains(CourseColors.DEFAULT),"the default colour is on the palette");

        check(!CourseColors.valid(null),"null is not a colour");
        check(!CourseColors.valid("DCD5FF"),"a missing # is rejected");
        check(!CourseColors.valid("#DCD5F"),"five digits are rejected");
        check(!CourseColors.valid("#DCD5FFF"),"seven digits are rejected");
        check(!CourseColors.valid("#DCD5FG"),"a non-hex digit is rejected");
        check(CourseColors.valid("#dcd5ff"),"lowercase is accepted");

        // Determinism: the whole point of hashing the title is that the same course keeps the same
        // colour across imports, reinstalls and devices.
        for(String title:new String[]{"软件工程","移动应用开发","高等数学","英语","","体育"})
            check(CourseColors.seed(title).equals(CourseColors.seed(title)),"seed is stable: "+title);

        // The seed is only a preference; pick() is what the app actually stores, so the seed only
        // has to stay on the palette.
        Set<String> used=new HashSet<>();
        for(int i=0;i<2000;i++) used.add(CourseColors.seed("课程"+i));
        check(used.size()==CourseColors.PALETTE.length,"2000 titles reach every seed colour");

        // The ported Python rule used int(hex[:8],16). In Java that overflows into the sign bit for
        // roughly half of all titles, and a bare % would then index out of bounds.
        Set<String> wide=new HashSet<>(Arrays.asList(CourseColors.PALETTE));
        for(int i=0;i<2000;i++)
            check(wide.contains(CourseColors.seed("压力测试"+i)),"seed stays on the palette at "+i);

        // Titles whose SHA-256 first byte has bit 63 set are the ones that used to blow up. Assert
        // at least one such title exists in the sample, so the check above is not vacuous.
        check(hasNegativeSeed(),"the sample includes a title with a negative hash value");

        check(CourseColors.pick("软件工程",new HashSet<String>()).equals(CourseColors.seed("软件工程")),
            "an unobstructed title keeps its own seed");

        // The property the whole feature exists for: walk a term's courses in the order the
        // timetable does, each picking against what the ones before it took, and no two titles end
        // up on the same colour while the palette has room.
        Set<String> worn=new LinkedHashSet<>();
        for(int i=0;i<CourseColors.PALETTE.length;i++){
            String picked=CourseColors.pick("课程"+i,worn);
            check(worn.add(picked),"course "+i+" does not reuse a colour: "+picked);
        }
        check(worn.size()==CourseColors.PALETTE.length,"a full palette's worth of courses all differ");

        // Only once the palette is exhausted may two titles share one.
        String overflow=CourseColors.pick("第三十一门课",worn);
        check(worn.contains(overflow),"past the palette's end, pick reuses a colour");

        // A new title must avoid visually similar hues, not merely different RGB strings.
        String seed=CourseColors.seed("软件工程");
        int at=CourseColors.PALETTE.length;
        for(int i=0;i<CourseColors.PALETTE.length;i++)
            if(CourseColors.PALETTE[i].equals(seed)) at=i;
        Set<String> blocked=new HashSet<>(Arrays.asList(seed));
        String moved=CourseColors.pick("软件工程",blocked);
        int movedAt=-1;
        for(int i=0;i<CourseColors.PALETTE.length;i++)
            if(CourseColors.PALETTE[i].equals(moved)) movedAt=i;
        check(movedAt/3!=at/3,"a blocked title should prefer a visibly different hue family");
        check(CourseColors.pick("软件工程",new HashSet<>(Arrays.asList(seed.toLowerCase(Locale.ROOT))))
            .equals(moved),"blocked colors are case insensitive");
        for(String candidate : palette)
            check(CourseColors.displayDistance(moved,seed) + .000001 >= CourseColors.displayDistance(candidate,seed),
                "selection maximizes displayed distance");
        Set<String> sample = new LinkedHashSet<>();
        for(int i=0;i<12;i++) {
            String chosen = CourseColors.pick("新课程"+i,sample);
            double distance = nearest(chosen,sample);
            for(String candidate:palette) if(!sample.contains(candidate))
                check(distance + .000001 >= nearest(candidate,sample),"maximin for multiple existing colors");
            sample.add(chosen);
        }

        // seed() falls back to PALETTE[0] when the digest is unavailable; that is only the same
        // thing as DEFAULT while these two agree.
        check(CourseColors.PALETTE[0].equals(CourseColors.DEFAULT),
            "the hash fallback and the default colour are the same entry");

        // Determinism: same title, same taken set, same answer — otherwise a re-import would
        // reshuffle the timetable's colours.
        Set<String> taken=new LinkedHashSet<>(Arrays.asList(CourseColors.seed("软件工程")));
        check(CourseColors.pick("高等数学",taken).equals(CourseColors.pick("高等数学",taken)),
            "pick is deterministic for the same blocked set");

        System.out.println(checks+" course colour checks passed");
    }

    static double nearest(String color,Set<String> used) {
        double result=Double.POSITIVE_INFINITY;
        for(String other:used) result=Math.min(result,CourseColors.displayDistance(color,other));
        return result;
    }
    /** True when some sampled title produces a hash whose top bit is set. */
    static boolean hasNegativeSeed(){
        for(int i=0;i<2000;i++){
            String title="压力测试"+i;
            long v=0;
            try{
                byte[] h=java.security.MessageDigest.getInstance("SHA-256")
                    .digest(title.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for(int k=0;k<8;k++) v=(v<<8)|(h[k]&0xFFL);
            }catch(Exception e){throw new AssertionError(e);}
            if(v<0) return true;
        }
        return false;
    }
}
