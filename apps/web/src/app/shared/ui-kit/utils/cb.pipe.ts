/* Vendored from @greenwhite/ui-kit (MIT) at commit 6472beb, path utils/cb.pipe.ts.
 * Per ADR-0015 this copy is ours to change; the commit above is only the
 * base for comparing later work in the kit. See NOTICE. */
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
