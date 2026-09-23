/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path utils/cb.pipe.ts.
 * Per ADR-0015: behaviour changes belong upstream; only compatibility
 * edits are made here and each is marked. See NOTICE. */
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'cb',
  standalone: true,
})
export class CbPipe implements PipeTransform {
  transform(callback: (...args: any[]) => any, a1?: unknown, a2?: unknown, a3?: unknown): any {
    return callback(a1, a2, a3);
  }
}
