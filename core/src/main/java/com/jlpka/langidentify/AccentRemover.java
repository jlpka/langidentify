package com.jlpka.langidentify;

/**
 * Removes accents and expands ligatures in Latin text. Maps accented characters to their ASCII
 * equivalents (e.g. é→e, ß→ss, æ→ae) using a direct-indexed lookup table. Non-ASCII characters
 * without a mapping are passed through as-is.
 */
public class AccentRemover {
  private static final String CONFIG =
      "ａªᵃₐáàăắằẵẳâấầẫẩǎåǻäǟãȧąāảȁȃạặậḁᴀⱥɐɑɒ:a,"
          + "æǽǣ:ae,"
          + "ｂḃḅḇʙƀɓƃꞵ:b,"
          + "ｃᶜćĉčċçḉᴄȼꞓɕↄ:c,"
          + "ｄďḋḑđḍḓḏðʤᴅɖɗ:d,"
          + "ｅᵉᴱéèĕêếềễểěëẽėȩḝęēḗḕẻȅȇẹệḛᴇɇǝəᵊɛɘɚɜɝɞɤ:e,"
          + "fｆḟƒⅎ:f,"
          + "ﬀ:ff,"
          + "ﬁ:fi,"
          + "ﬃ:ffi,"
          + "ﬂ:fl,"
          + "ｇǵğĝǧġģḡᵹɡɢǥɠɣˠƣ:g,"
          + "ｈʰĥȟḧḣḩħḥḫẖʜƕɦʱɧ:h,"
          + "ｉⅰⁱᵢíìĭîǐïḯĩįīỉȉȋịḭıɪᴉɨᵻɩ:i,"
          + "ⅱ:ii,"
          + "ⅲ:iii,"
          + "ĳ:ij,"
          + "ⅳ:iv,"
          + "ｊʲĵǰȷᴊʝɟʄ:j,"
          + "ｋḱǩķḳḵƙⱪꝃʞ:k,"
          + "ｌˡĺľļłḷḹḻŀʟƚⱡɫɬɭɮƛʎ:l,"
          + "ᵐᴹḿṁṃᴍɱ:m,"
          + "ｎⁿńǹňñṅņṇṋṉɴᵰɲƞꞑᶇɳŋᵑ:n,"
          + "ｏºᵒóòŏôốồỗổǒöȫőõȭȯøǿǫǭōṓṑỏȍȏơớờỡởợọộᴏɔᵓᴖᴗɵɷⱷ:o,"
          + "œɶ:oe,"
          + "ꝏ:oo,"
          + "ȣ:ou,"
          + "ｐṕṗᴘƥɸⱷ:p,"
          + "ꝗĸ:q,"
          + "ｒʳŕřṙŗȓṛṝṟꝛʀɍɹᴚɺɻɼɽɾɿʁ:r,"
          + "ｓśṥŝšṧṡşṣșſꜱʂʃ:s,"
          + "ß:ss,"
          + "ﬆ:st,"
          + "ｔᵀťẗṫţṭțṱṯʨʦᴛŧƭʈȶʇ:t,"
          + "ʧ:tf,"
          + "ｕᵘúùŭûǔůüǘǜǚǖűũųūṻủȕȗưứừữửựụṳṷṵʉɥɯɰʊ:u,"
          + "ｖⅴᵛṽṿᴠʋⱱʌ:v,"
          + "ⅵ:vi,"
          + "ⅶ:vii,"
          + "ｗʷẃẁŵẅʍ:w,"
          + "ｘⅹₓẍẋꭓ:x,"
          + "ｙʸýỳŷÿỹẏȳỷỵʏɏƴỿȝ:y,"
          + "źẑžżẓẕᴢƶȥʐʑ:z";

  // Direct-indexed lookup table: table[ch - 0x80] = replacement string, or null.
  private final String[] table;

  public AccentRemover() {
    // First pass: find max char value to size the table.
    int maxChar = 0x80;
    for (int i = 0; i < CONFIG.length(); i++) {
      char c = CONFIG.charAt(i);
      if (c != ':' && c != ',') {
        if (c > maxChar) maxChar = c;
      }
    }
    table = new String[maxChar - 0x80 + 1];

    // Second pass: parse config and populate table.
    for (String entry : CONFIG.split(",")) {
      int colonIdx = entry.lastIndexOf(':');
      String chars = entry.substring(0, colonIdx);
      String replacement = entry.substring(colonIdx + 1);
      for (int i = 0; i < chars.length(); i++) {
        char c = chars.charAt(i);
        if (c >= 0x80) {
          table[c - 0x80] = replacement;
        }
      }
    }
  }

  /**
   * Returns the input with accented Latin characters replaced by their ASCII equivalents. Non-ASCII
   * characters without a mapping are passed through unchanged. If the input contains no non-ASCII
   * characters, it is returned as-is (no allocation).
   */
  public CharSequence remove(CharSequence text) {
    int len = text.length();
    // Quick scan: if no chars >= 0x80, return as-is.
    boolean needsWork = false;
    for (int i = 0; i < len; i++) {
      if (text.charAt(i) >= 0x80) {
        needsWork = true;
        break;
      }
    }
    if (!needsWork) {
      return text;
    }

    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        sb.append(c);
      } else {
        int idx = c - 0x80;
        if (idx < table.length && table[idx] != null) {
          sb.append(table[idx]);
        } else {
          sb.append(c); // unmapped non-ASCII: pass through
        }
      }
    }
    return sb;
  }
}
