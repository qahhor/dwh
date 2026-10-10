import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CustomField } from '@core/models/custom-field.models';
import { Project } from '@core/models/task.models';
import { ApiService } from '@core/services/api.service';
import { inScreen, Screen } from '@testing/in-screen';
import { ProjectCreateForm, ProjectEditForm } from '../projects.models';
import { ProjectModalsComponent } from './project-modals.component';

const PROJECT: Project = {
  id: 7,
  name: 'Склад',
  description: 'Учёт остатков',
  state: 'A',
  attributes: { region: 'tsh', audited: 'true' },
  createdAt: '2026-09-01T00:00:00Z',
};

const field = (code: string, name: string, fieldType: string, optionsJson?: string) =>
  ({
    id: code.length,
    entityType: 'project',
    code,
    name,
    fieldType,
    optionsJson,
    isRequired: false,
    orderNo: 1,
    createdAt: '2026-09-01T00:00:00Z',
  }) as CustomField;

const FIELDS = [
  field('region', 'Регион', 'select', JSON.stringify([{ value: 'tsh', label: 'Ташкент' }])),
  field('audited', 'Проверен', 'boolean'),
];

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({
    imports: [ProjectModalsComponent],
    providers: [{ provide: ApiService, useValue: { get: vi.fn(() => of({ items: [], nextCursor: null })) } }],
  });
  const fixture = TestBed.createComponent(ProjectModalsComponent);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return { fixture, screen: inScreen(fixture.nativeElement) };
}

const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (screen: Screen, label: string) =>
  [...screen.querySelectorAll('button')].find((node: Element) => text(node) === label) as HTMLButtonElement | undefined;
const nameError = (screen: Screen, id: string) =>
  text(screen.querySelector(`smt-control:has(#${id}) .smt-control__error`));

function click(fixture: ComponentFixture<ProjectModalsComponent>, node: HTMLElement | undefined | null) {
  if (!node) throw new Error('nothing to click');
  node.click();
  fixture.detectChanges();
}

function type(fixture: ComponentFixture<ProjectModalsComponent>, screen: Screen, id: string, value: string) {
  const input = screen.querySelector(`#${id}`) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

describe('ProjectModalsComponent', () => {
  it('shows an opened project record with its custom fields named and their values as labels', () => {
    const { fixture, screen } = render({
      routeRecordId: '7',
      viewingProject: PROJECT,
      projectCustomFields: FIELDS,
    });
    const closed = vi.fn();
    fixture.componentInstance.closeRecordView.subscribe(closed);

    expect(text(screen.querySelector('[data-record-id="7"] h3'))).toBe('Склад');
    expect(text(screen.querySelector('[data-record-id="7"]'))).toContain('Учёт остатков');
    expect(
      [...screen.querySelectorAll('.attr-stack-item')].map((item: Element) => [...item.children].map(text)),
    ).toEqual([
      ['Регион:', 'Ташкент'],
      ['Проверен:', 'Да'],
    ]);
    click(fixture, button(screen, 'Вернуться к списку'));
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('offers to load a record again after a failure, but not when it does not exist', () => {
    const { fixture, screen } = render({ routeRecordId: '7', recordError: true });
    const reload = vi.fn();
    fixture.componentInstance.loadRecordView.subscribe(reload);

    expect(text(screen.querySelector('[role="alert"] p'))).toBe('Не удалось загрузить запись.');
    click(fixture, button(screen, 'Повторить'));
    expect(reload).toHaveBeenCalledWith('7');

    fixture.componentRef.setInput('recordNotFound', true);
    fixture.detectChanges();
    expect(text(screen.querySelector('[role="alert"] p'))).toBe('404 — Запись не найдена или недоступна.');
    expect(button(screen, 'Повторить')).toBeUndefined();
  });

  it('writes the new project into the form, submits it and shows why the save failed', () => {
    const form: ProjectCreateForm = { name: '', description: '', attributes: {} };
    const { fixture, screen } = render({ isCreateModalOpen: true, createForm: form });
    const submitted = vi.fn();
    const closing = vi.fn();
    fixture.componentInstance.submitCreateProject.subscribe(submitted);
    fixture.componentInstance.requestCloseCreate.subscribe(closing);

    expect(text(screen.querySelector('.smt-modal__title'))).toBe('Создание нового проекта');
    type(fixture, screen, 'project-create-name', 'Логистика');
    click(fixture, button(screen, 'Создать проект'));
    click(fixture, button(screen, 'Отмена'));
    expect(form.name).toBe('Логистика');
    expect(submitted).toHaveBeenCalledTimes(1);
    expect(closing).toHaveBeenCalledTimes(1);

    fixture.componentRef.setInput('createSaveError', 'Проект с таким названием уже есть');
    fixture.detectChanges();
    expect(text(screen.querySelector('[data-testid="project-create-save-error"]'))).toBe(
      'Проект с таким названием уже есть',
    );
  });

  it('asks for the name of a new project once it is submitted empty or left empty', () => {
    const { fixture, screen } = render({ isCreateModalOpen: true, createForm: { name: ' ', description: '' } });
    expect(nameError(screen, 'project-create-name')).toBe('');

    fixture.componentRef.setInput('isCreateSubmitted', true);
    fixture.detectChanges();
    expect(nameError(screen, 'project-create-name')).toBe('Пожалуйста, укажите название проекта');

    fixture.componentRef.setInput('isCreateSubmitted', false);
    fixture.componentRef.setInput('createForm', { name: '', description: '' });
    fixture.detectChanges();
    (screen.querySelector('#project-create-name') as HTMLInputElement).dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(nameError(screen, 'project-create-name')).toBe('Обязательное поле');
  });

  it('edits a loaded project: shows it, writes the chosen state and saves, or offers a retry when it failed', () => {
    const form: ProjectEditForm = { name: 'Склад', description: '', state: 'A', attributes: {} };
    const { fixture, screen } = render({ isEditModalOpen: true, editLoadError: true, editForm: form });
    const retried = vi.fn();
    const saved = vi.fn();
    fixture.componentInstance.retryEditLoad.subscribe(retried);
    fixture.componentInstance.submitEditProject.subscribe(saved);

    expect(button(screen, 'Сохранить')).toBeUndefined();
    click(fixture, button(screen, 'Повторить загрузку проекта'));
    expect(retried).toHaveBeenCalledTimes(1);

    fixture.componentRef.setInput('editLoadError', false);
    fixture.componentRef.setInput('editingProject', PROJECT);
    fixture.detectChanges();
    expect((screen.querySelector('#project-edit-name') as HTMLInputElement).value).toBe('Склад');
    click(fixture, screen.querySelector('#project-edit-state'));
    const archived = [...document.querySelectorAll<HTMLElement>('.smt-select__option')].find(
      (option) => text(option) === 'В архиве',
    );
    click(fixture, archived);
    click(fixture, button(screen, 'Сохранить'));

    expect(form.state).toBe('P');
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('shows the server field errors of a save under the fields', () => {
    const { fixture, screen } = render({
      isEditModalOpen: true,
      editingProject: PROJECT,
      editForm: { name: 'Склад', description: '', state: 'A', attributes: {} },
      editErrors: { fields: { name: 'Имя занято', description: 'Слишком длинное' }, other: [] },
    });
    TestBed.tick();
    fixture.detectChanges();
    expect(nameError(screen, 'project-edit-name')).toBe('Имя занято');
    expect(text(screen.querySelector('smt-control:has(#project-edit-description) .smt-control__error'))).toBe(
      'Слишком длинное',
    );
    expect(screen.querySelector('#project-edit-name')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('closes a dialog that could not load its project with one Close button', () => {
    const { fixture, screen } = render({ isEditModalOpen: true, editLoadError: true });
    const closed = vi.fn();
    fixture.componentInstance.requestCloseEdit.subscribe(closed);
    click(fixture, button(screen, 'Закрыть'));
    expect(closed).toHaveBeenCalledTimes(1);
    expect(button(screen, 'Отмена')).toBeUndefined();
  });
});
