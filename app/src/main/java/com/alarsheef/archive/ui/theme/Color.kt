package com.alarsheef.archive.ui.theme

import androidx.compose.ui.graphics.Color

// الثيم الداكن المعتمد (2/10/2026): تدرّج كحلي–تركوازي داكن + زجاج خافت + لمسة سماوية.
// كل الشاشات تقرأ من هذه الرموز، فتغيير القيم هنا يغيّر التطبيق كله. نسب التباين
// المقاسة: نص أبيض فوق الزجاج 12.8–13.7:1 (رئيسي) و7.1–7.5:1 (ثانوي).

// علامات (شارات الصور وغيرها) — لم تتغير
val Teal = Color(0xFF12A594)
val Amber = Color(0xFFF0A63A)

// اللون المميّز فوق الداكن: أيقونات/أزرار/مؤشرات. والنص فوقه OnAccent (كحلي داكن).
val TealBright = Color(0xFF5EEAD4)
val Accent = TealBright
val OnAccent = Color(0xFF04252C)
val Mint = Color(0xFFE6FFFB)

// تدرّج الخلفية الرئيسي + كرات الضوء
val GradientStart = Color(0xFF031F26)
val GradientMid = Color(0xFF07223F)
val GradientEnd = Color(0xFF1A1858)
val GlowCyan = Color(0xFF22D3EE)
val GlowIndigo = Color(0xFF7C3AED)

// زجاج: أسطح بيضاء شديدة الشفافية فوق خلفية داكنة + خيط شعري
val GlassSoft = Color(0x0FFFFFFF)   // 6%  — صفوف الإعدادات داخل اللوحات
val Glass = Color(0x14FFFFFF)       // 8%  — بطاقة/صف أساسي
val GlassStrong = Color(0x1AFFFFFF) // 10% — بلاطات أيقونات وشرائح علوية
val GlassBorder = Color(0x29FFFFFF) // 16% — خيط حدّي
val GlassSheet = Color(0xF20B2540)  // لوح شبه معتم (قوائم/ألواح تحتاج قراءة فوق محتوى)
val PanelSurface = Color(0xF2082A36) // حوارات وقوائم منسدلة
val AppBackground = Color.Transparent
val Surface = Glass
val TextPrimary = Color(0xFFFFFFFF)
val ErrorRed = Color(0xFFFF8A8A)
val TextMuted = Color(0xB8FFFFFF)   // 72% — نص ثانوي
val OutlineSoft = Color(0x29FFFFFF)
val SurfaceTinted = Color(0x0FFFFFFF)

// شارات أنواع الملفات (ثابتة الدلالة: أحمر PDF، أزرق مستند نصي، أخضر جدول)
val BadgePdf = Color(0xFFD9362B)
val BadgeDoc = Color(0xFF2563EB)
val BadgeSheet = Color(0xFF16A34A)
val BadgeOther = Color(0xFF64748B)
