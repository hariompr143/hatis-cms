import { PageResponse, request, query } from '@/lib/api';
import { ErrorPanel } from '@/components/ErrorPanel';
import { EmptyState } from '@/components/EmptyState';
import { StatusBadge } from '@/components/StatusBadge';
import { formatRelativeTime } from '@/lib/format';
import { accessToken } from '@/lib/session';

export const metadata = { title: 'Content — HATIS' };

interface ContentSummary {
  id: string;
  slug: string;
  locale: string;
  status: string;
  projectId: string;
  contentTypeId: string;
  publishedAt: string | null;
  updatedAt: string;
}

interface Project {
  id: string;
  name: string;
  slug: string;
}

export default async function ContentPage({
  searchParams,
}: {
  searchParams: Promise<{ project?: string; status?: string; page?: string }>;
}) {
  const params = await searchParams;
  const token = await accessToken();
  const page = Math.max(Number(params.page ?? '0'), 0);

  let projects: Project[] = [];
  let items: ContentSummary[] = [];
  let totalPages = 0;
  let error: unknown = null;

  try {
    const projectPage = await request<PageResponse<Project>>('/v1/projects?page=0&size=100', {
      accessToken: token ?? undefined,
    });
    projects = projectPage.items;

    const projectId = params.project ?? projects[0]?.id;
    if (projectId) {
      const contentPage = await request<PageResponse<ContentSummary>>(
        `/v1/content/items${query({ projectId, status: params.status, page, size: 25 })}`,
        { accessToken: token ?? undefined },
      );
      items = contentPage.items;
      totalPages = contentPage.totalPages;
    }
  } catch (cause) {
    error = cause;
  }

  const selectedProject = params.project ?? projects[0]?.id ?? '';

  return (
    <>
      <div className="page-head">
        <h1>Content</h1>
        <p className="muted">Drafts are never served; only published versions reach the delivery API.</p>
      </div>

      {error ? <ErrorPanel error={error} /> : null}

      <div className="card">
        <form method="GET">
          <label>
            Project
            <select name="project" defaultValue={selectedProject}>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Status
            <select name="status" defaultValue={params.status ?? ''}>
              <option value="">All</option>
              <option value="DRAFT">Draft</option>
              <option value="IN_REVIEW">In review</option>
              <option value="APPROVED">Approved</option>
              <option value="PUBLISHED">Published</option>
              <option value="ARCHIVED">Archived</option>
            </select>
          </label>
          <button type="submit">Filter</button>
        </form>
      </div>

      {items.length === 0 && !error ? <EmptyState message="No content matches this filter." /> : null}

      {items.length > 0 ? (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>Slug</th>
                <th>Locale</th>
                <th>Status</th>
                <th>Published</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{item.slug}</td>
                  <td>{item.locale}</td>
                  <td>
                    <StatusBadge status={item.status} />
                  </td>
                  <td className="muted">{formatRelativeTime(item.publishedAt)}</td>
                  <td className="muted">{formatRelativeTime(item.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {totalPages > 1 ? (
        <p className="muted">
          Page {page + 1} of {totalPages}
        </p>
      ) : null}
    </>
  );
}
