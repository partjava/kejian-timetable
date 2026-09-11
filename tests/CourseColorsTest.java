import com.kejian.app.CourseColors;
import java.util.*;

public class CourseColorsTest {
    static int checks=0;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}

    public static void main(String[] args){
        // The seed pool is interpolated into the model prompt, so its size is part of the AI
        // contract rather than a free choice.
        check(CourseColors.SUGGESTED.length==6,"seed pool stays at six");

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

        // Distinct titles may collide (six colours, more courses), but a sane spread should not put
        // everything on one colour.
        Set<String> used=new HashSet<>();
        for(int i=0;i<200;i++) used.add(CourseColors.seed("课程"+i));
        check(used.size()==CourseColors.SUGGESTED.length,"200 titles reach every seed colour");

        // The ported Python rule used int(hex[:8],16). In Java that overflows into the sign bit for
        // roughly half of all titles, and a bare % would then index out of bounds.
        Set<String> wide=new HashSet<>(Arrays.asList(CourseColors.SUGGESTED));
        for(int i=0;i<2000;i++)
            check(wide.contains(CourseColors.seed("压力测试"+i)),"seed stays on the palette at "+i);

        // Titles whose SHA-256 first byte has bit 63 set are the ones that used to blow up. Assert
        // at least one such title exists in the sample, so the check above is not vacuous.
        check(hasNegativeSeed(),"the sample includes a title with a negative hash value");

        System.out.println(checks+" course colour checks passed");
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
