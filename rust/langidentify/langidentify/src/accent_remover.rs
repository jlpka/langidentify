// Copyright 2026 Jeremy Lilley
// Licensed under the Apache License, Version 2.0

/// Removes accents and expands ligatures in Latin text. Maps accented characters
/// to their ASCII equivalents (e.g. e\u{0301}->e, \u{00DF}->ss, \u{00E6}->ae)
/// using a direct-indexed lookup table.
pub struct AccentRemover {
    /// table[ch - 0x80] = replacement string, or None.
    table: Vec<Option<&'static str>>,
}

const CONFIG: &str = concat!(
    "\u{FF41}\u{00AA}\u{1D43}\u{2090}\u{00E1}\u{00E0}\u{0103}\u{1EAF}\u{1EB1}\u{1EB5}\u{1EB3}",
    "\u{00E2}\u{1EA5}\u{1EA7}\u{1EAB}\u{1EA9}\u{01CE}\u{00E5}\u{01FB}\u{00E4}\u{01DF}\u{00E3}",
    "\u{0227}\u{0105}\u{0101}\u{1EA3}\u{0201}\u{0203}\u{1EA1}\u{1EB7}\u{1EAD}\u{1E01}\u{1D00}",
    "\u{2C65}\u{0250}\u{0251}\u{0252}:a,",
    "\u{00E6}\u{01FD}\u{01E3}:ae,",
    "\u{FF42}\u{1E03}\u{1E05}\u{1E07}\u{0299}\u{0180}\u{0253}\u{0183}\u{A7B5}:b,",
    "\u{FF43}\u{1D9C}\u{0107}\u{0109}\u{010D}\u{010B}\u{00E7}\u{1E09}\u{1D04}\u{023C}\u{A793}\u{0255}\u{2184}:c,",
    "\u{FF44}\u{010F}\u{1E0B}\u{1E11}\u{0111}\u{1E0D}\u{1E13}\u{1E0F}\u{00F0}\u{02A4}\u{1D05}\u{0256}\u{0257}:d,",
    "\u{FF45}\u{1D49}\u{1D31}\u{00E9}\u{00E8}\u{0115}\u{00EA}\u{1EBF}\u{1EC1}\u{1EC5}\u{1EC3}",
    "\u{011B}\u{00EB}\u{1EBD}\u{0117}\u{0229}\u{1E1D}\u{0119}\u{0113}\u{1E17}\u{1E15}\u{1EBB}",
    "\u{0205}\u{0207}\u{1EB9}\u{1EC7}\u{1E1B}\u{1D07}\u{0247}\u{01DD}\u{0259}\u{1D4A}\u{025B}",
    "\u{0258}\u{025A}\u{025C}\u{025D}\u{025E}\u{0264}:e,",
    "f\u{FF46}\u{1E1F}\u{0192}\u{214E}:f,",
    "\u{FB00}:ff,",
    "\u{FB01}:fi,",
    "\u{FB03}:ffi,",
    "\u{FB02}:fl,",
    "\u{FF47}\u{01F5}\u{011F}\u{011D}\u{01E7}\u{0121}\u{0123}\u{1E21}\u{1D79}\u{0261}\u{0262}\u{01E5}\u{0260}\u{0263}\u{02E0}\u{01A3}:g,",
    "\u{FF48}\u{02B0}\u{0125}\u{021F}\u{1E27}\u{1E23}\u{1E29}\u{0127}\u{1E25}\u{1E2B}\u{1E96}\u{029C}\u{0195}\u{0266}\u{02B1}\u{0267}:h,",
    "\u{FF49}\u{2170}\u{2071}\u{1D62}\u{00ED}\u{00EC}\u{012D}\u{00EE}\u{01D0}\u{00EF}\u{1E2F}",
    "\u{0129}\u{012F}\u{012B}\u{1EC9}\u{0209}\u{020B}\u{1ECB}\u{1E2D}\u{0131}\u{026A}\u{1D09}",
    "\u{0268}\u{1D7B}\u{0269}:i,",
    "\u{2171}:ii,",
    "\u{2172}:iii,",
    "\u{0133}:ij,",
    "\u{2173}:iv,",
    "\u{FF4A}\u{02B2}\u{0135}\u{01F0}\u{0237}\u{1D0A}\u{029D}\u{025F}\u{0284}:j,",
    "\u{FF4B}\u{1E31}\u{01E9}\u{0137}\u{1E33}\u{1E35}\u{0199}\u{2C6A}\u{A743}\u{029E}:k,",
    "\u{FF4C}\u{02E1}\u{013A}\u{013E}\u{013C}\u{0142}\u{1E37}\u{1E39}\u{1E3B}\u{013F}\u{029F}\u{019A}\u{2C61}\u{026B}\u{026C}\u{026D}\u{026E}\u{019B}\u{028E}:l,",
    "\u{1D50}\u{1D39}\u{1E3F}\u{1E41}\u{1E43}\u{1D0D}\u{0271}:m,",
    "\u{FF4E}\u{207F}\u{0144}\u{01F9}\u{0148}\u{00F1}\u{1E45}\u{0146}\u{1E47}\u{1E4B}\u{1E49}\u{0274}\u{1D70}\u{0272}\u{019E}\u{A791}\u{1D87}\u{0273}\u{014B}\u{1D51}:n,",
    "\u{FF4F}\u{00BA}\u{1D52}\u{00F3}\u{00F2}\u{014F}\u{00F4}\u{1ED1}\u{1ED3}\u{1ED7}\u{1ED5}",
    "\u{01D2}\u{00F6}\u{022B}\u{0151}\u{00F5}\u{022D}\u{022F}\u{00F8}\u{01FF}\u{01EB}\u{01ED}",
    "\u{014D}\u{1E53}\u{1E51}\u{1ECF}\u{020D}\u{020F}\u{01A1}\u{1EDB}\u{1EDD}\u{1EE1}\u{1EDF}",
    "\u{1EE3}\u{1ECD}\u{1ED9}\u{1D0F}\u{0254}\u{1D53}\u{1D16}\u{1D17}\u{0275}\u{0277}\u{2C77}:o,",
    "\u{0153}\u{0276}:oe,",
    "\u{A74F}:oo,",
    "\u{0223}:ou,",
    "\u{FF50}\u{1E55}\u{1E57}\u{1D18}\u{01A5}\u{0278}\u{2C77}:p,",
    "\u{A757}\u{0138}:q,",
    "\u{FF52}\u{02B3}\u{0155}\u{0159}\u{1E59}\u{0157}\u{0213}\u{1E5B}\u{1E5D}\u{1E5F}\u{A75B}\u{0280}\u{024D}\u{0279}\u{1D1A}\u{027A}\u{027B}\u{027C}\u{027D}\u{027E}\u{027F}\u{0281}:r,",
    "\u{FF53}\u{015B}\u{1E65}\u{015D}\u{0161}\u{1E67}\u{1E61}\u{015F}\u{1E63}\u{0219}\u{017F}\u{A731}\u{0282}\u{0283}:s,",
    "\u{00DF}:ss,",
    "\u{FB06}:st,",
    "\u{FF54}\u{1D40}\u{0165}\u{1E97}\u{1E6B}\u{0163}\u{1E6D}\u{021B}\u{1E71}\u{1E6F}\u{02A8}\u{02A6}\u{1D1B}\u{0167}\u{01AD}\u{0288}\u{0236}\u{0287}:t,",
    "\u{02A7}:tf,",
    "\u{FF55}\u{1D58}\u{00FA}\u{00F9}\u{016D}\u{00FB}\u{01D4}\u{016F}\u{00FC}\u{01D8}\u{01DC}",
    "\u{01DA}\u{01D6}\u{0171}\u{0169}\u{0173}\u{016B}\u{1E7B}\u{1E7D}\u{0216}\u{0218}",  // Note: 0216/0218 are not u-related but keeping for compat
    "\u{01B0}\u{1EE9}\u{1EEB}\u{1EEF}\u{1EED}\u{1EF1}\u{1EE5}\u{1E73}\u{1E77}\u{1E75}",
    "\u{0289}\u{0265}\u{026F}\u{0270}\u{028A}:u,",
    "\u{FF56}\u{2174}\u{1D5B}\u{1E7D}\u{1E7F}\u{1D20}\u{028B}\u{2C71}\u{028C}:v,",
    "\u{2175}:vi,",
    "\u{2176}:vii,",
    "\u{FF57}\u{02B7}\u{1E83}\u{1E81}\u{0175}\u{1E85}\u{028D}:w,",
    "\u{FF58}\u{2179}\u{2093}\u{1E8D}\u{1E8B}\u{AB53}:x,",
    "\u{FF59}\u{02B8}\u{00FD}\u{1EF3}\u{0177}\u{00FF}\u{1EF9}\u{1E8F}\u{0233}\u{1EF7}\u{1EF5}\u{028F}\u{024F}\u{01B4}\u{1EFF}\u{021D}:y,",
    "\u{017A}\u{1E91}\u{017E}\u{017C}\u{1E93}\u{1E95}\u{1D22}\u{01B6}\u{0225}\u{0290}\u{0291}:z",
);

impl AccentRemover {
    pub fn new() -> Self {
        // First pass: find max char value to size the table.
        let mut max_char = 0x80u32;
        for c in CONFIG.chars() {
            if c != ':' && c != ',' {
                let cp = c as u32;
                if cp > max_char {
                    max_char = cp;
                }
            }
        }
        let table_size = (max_char - 0x80 + 1) as usize;
        let mut table = vec![None; table_size];

        // Parse config and populate table.
        for entry in CONFIG.split(',') {
            if let Some(colon_idx) = entry.rfind(':') {
                let chars_part = &entry[..colon_idx];
                let replacement = &entry[colon_idx + 1..];
                // SAFETY: replacement points into a static str
                let replacement: &'static str = unsafe { &*(replacement as *const str) };
                for c in chars_part.chars() {
                    let cp = c as u32;
                    if cp >= 0x80 {
                        let idx = (cp - 0x80) as usize;
                        if idx < table.len() {
                            table[idx] = Some(replacement);
                        }
                    }
                }
            }
        }

        AccentRemover { table }
    }

    /// Removes accents from the given text. If the input contains no non-ASCII
    /// characters, it is returned as-is (no allocation).
    pub fn remove<'a>(&self, text: &'a str) -> std::borrow::Cow<'a, str> {
        if text.bytes().all(|b| b < 0x80) {
            return std::borrow::Cow::Borrowed(text);
        }

        let mut result = String::with_capacity(text.len());
        for c in text.chars() {
            let cp = c as u32;
            if cp < 0x80 {
                result.push(c);
            } else {
                let idx = (cp - 0x80) as usize;
                if idx < self.table.len() {
                    if let Some(replacement) = self.table[idx] {
                        result.push_str(replacement);
                        continue;
                    }
                }
                result.push(c); // unmapped non-ASCII: pass through
            }
        }
        std::borrow::Cow::Owned(result)
    }
}

impl Default for AccentRemover {
    fn default() -> Self {
        Self::new()
    }
}
