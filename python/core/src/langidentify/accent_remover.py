# Copyright 2026 Jeremy Lilley (jeremy@jlilley.net)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Removes accents and expands ligatures in Latin text.

Maps accented characters to their ASCII equivalents (e.g. e->e, ss->ss, ae->ae)
using a direct-indexed lookup table.
"""

_CONFIG = (
    "ａªᵃₐáàăắằẵẳâấầẫẩǎåǻäǟãȧąāảȁȃạặậḁᴀⱥɐɑɒ:a,"
    "æǽǣ:ae,"
    "ｂḃḅḇʙƀɓƃꞵ:b,"
    "ｃᶜćĉčċçḉᴄȼꞓɕↄ:c,"
    "ｄďḋḑđḍḓḏðʤᴅɖɗ:d,"
    "ｅᵉᴱéèĕêếềễểěëẽėȩḝęēḗḕẻȅȇẹệḛᴇɇǝəᵊɛɘɚɜɝɞɤ:e,"
    "fｆḟƒⅎ:f,"
    "ﬀ:ff,"
    "ﬁ:fi,"
    "ﬃ:ffi,"
    "ﬂ:fl,"
    "ｇǵğĝǧġģḡᵹɡɢǥɠɣˠƣ:g,"
    "ｈʰĥȟḧḣḩħḥḫẖʜƕɦʱɧ:h,"
    "ｉⅰⁱᵢíìĭîǐïḯĩįīỉȉȋịḭıɪᴉɨᵻɩ:i,"
    "ⅱ:ii,"
    "ⅲ:iii,"
    "ĳ:ij,"
    "ⅳ:iv,"
    "ｊʲĵǰȷᴊʝɟʄ:j,"
    "ｋḱǩķḳḵƙⱪꝃʞ:k,"
    "ｌˡĺľļłḷḹḻŀʟƚⱡɫɬɭɮƛʎ:l,"
    "ᵐᴹḿṁṃᴍɱ:m,"
    "ｎⁿńǹňñṅņṇṋṉɴᵰɲƞꞑᶇɳŋᵑ:n,"
    "ｏºᵒóòŏôốồỗổǒöȫőõȭȯøǿǫǭōṓṑỏȍȏơớờỡởợọộᴏɔᵓᴖᴗɵɷⱷ:o,"
    "œɶ:oe,"
    "ꝏ:oo,"
    "ȣ:ou,"
    "ｐṕṗᴘƥɸⱷ:p,"
    "ꝗĸ:q,"
    "ｒʳŕřṙŗȓṛṝṟꝛʀɍɹᴚɺɻɼɽɾɿʁ:r,"
    "ｓśṥŝšṧṡşṣșſꜱʂʃ:s,"
    "ß:ss,"
    "ﬆ:st,"
    "ｔᵀťẗṫţṭțṱṯʨʦᴛŧƭʈȶʇ:t,"
    "ʧ:tf,"
    "ｕᵘúùŭûǔůüǘǜǚǖűũųūṻủȕȗưứừữửựụṳṷṵʉɥɯɰʊ:u,"
    "ｖⅴᵛṽṿᴠʋⱱʌ:v,"
    "ⅵ:vi,"
    "ⅶ:vii,"
    "ｗʷẃẁŵẅʍ:w,"
    "ｘⅹₓẍẋꭓ:x,"
    "ｙʸýỳŷÿỹẏȳỷỵʏɏƴỿȝ:y,"
    "źẑžżẓẕᴢƶȥʐʑ:z"
)


class AccentRemover:
    """Removes accents and expands ligatures in Latin text."""

    def __init__(self):
        self._table = {}
        for entry in _CONFIG.split(","):
            colon_idx = entry.rindex(":")
            chars = entry[:colon_idx]
            replacement = entry[colon_idx + 1:]
            for c in chars:
                if ord(c) >= 0x80:
                    self._table[c] = replacement

    def remove(self, text):
        """Returns the input with accented Latin characters replaced by ASCII equivalents.

        Non-ASCII characters without a mapping are passed through unchanged.
        If the input contains no non-ASCII characters, it is returned as-is.
        """
        if all(ord(c) < 0x80 for c in text):
            return text
        parts = []
        for c in text:
            if ord(c) < 0x80:
                parts.append(c)
            else:
                parts.append(self._table.get(c, c))
        return "".join(parts)
