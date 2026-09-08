#!/usr/bin/env node
'use strict';

/**
 * Dat mat khau dang nhap cho web dashboard.
 *
 *   node scripts/set-password.js              -> hoi mat khau (an khi go)
 *   node scripts/set-password.js "matkhau"    -> dat truc tiep (dung cho script cai dat)
 */

require('dotenv').config();

const readline = require('readline');
const { setWebPassword } = require('../src/auth');

function askHidden(question) {
  return new Promise((resolve) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    const onData = (char) => {
      const s = String(char);
      if (s === '\n' || s === '\r' || s === '') {
        process.stdin.removeListener('data', onData);
        return;
      }
      readline.clearLine(process.stdout, 0);
      readline.cursorTo(process.stdout, 0);
      process.stdout.write(question + '*'.repeat(rl.line.length));
    };
    process.stdin.on('data', onData);
    rl.question(question, (answer) => {
      process.stdout.write('\n');
      rl.close();
      resolve(answer);
    });
  });
}

async function main() {
  const fromArg = process.argv[2];

  if (fromArg) {
    if (fromArg.length < 6) {
      console.error('Mat khau phai dai it nhat 6 ky tu.');
      process.exit(1);
    }
    setWebPassword(fromArg);
    console.log('Da dat mat khau. Moi phien dang nhap cu da bi huy.');
    return;
  }

  const p1 = await askHidden('Mat khau moi: ');
  if (p1.length < 6) {
    console.error('Mat khau phai dai it nhat 6 ky tu.');
    process.exit(1);
  }
  const p2 = await askHidden('Nhap lai     : ');
  if (p1 !== p2) {
    console.error('Hai lan nhap khong khop.');
    process.exit(1);
  }

  setWebPassword(p1);
  console.log('Da dat mat khau. Moi phien dang nhap cu da bi huy.');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
