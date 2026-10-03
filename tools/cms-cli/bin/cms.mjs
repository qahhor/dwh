#!/usr/bin/env node
// SmartupCMS developer CLI (plan 10/10, item 6.1). Node standard library only; runs on Windows, Linux and macOS.
import { run } from '../lib/main.mjs';

run(process.argv.slice(2));
