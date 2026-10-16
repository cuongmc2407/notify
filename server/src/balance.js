'use strict';

/**
 * Nhan dien thong bao bien dong so du cua ngan hang, de tach khoi quang cao ma
 * chinh app ngan hang day ra (cung package nen bo loc theo app khong tach duoc).
 *
 * Dau hieu: mot so tien co dau +/- ("+2.100.000", "-50,000VND", "PS:+100.000",
 * "+ 100,000") di kem tu khoa so du / giao dich ("So du", "SD", "GD", "PS").
 * Quang cao cung hay co so ("loi toi 3,5%", "ve chi tu 74K") nhung hiem khi
 * co du ca hai.
 */

function normalize(s) {
  return String(s || '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[đĐ]/g, 'd')
    .toLowerCase();
}

// Dau +/- phai dung dau mot cum, nen "800xx1799-6289BFT" (ma tham chieu) khong tinh.
const SIGNED_AMOUNT = /(?:^|[^\w.,])[+-]\s?(?:\d{1,3}(?:[.,]\d{3})+|\d+\s?(?:vnd|d)\b)/;
const BALANCE_WORD = /so du|\bsd\b|\bps\b|\bgd\b|giao dich|bien dong/;
const BALANCE_PHRASE = /bien dong so du/;

function isBalanceChange(title, body) {
  const text = normalize(title) + '\n' + normalize(body);
  if (BALANCE_PHRASE.test(text)) return true;
  return SIGNED_AMOUNT.test(text) && BALANCE_WORD.test(text);
}

module.exports = { isBalanceChange };
